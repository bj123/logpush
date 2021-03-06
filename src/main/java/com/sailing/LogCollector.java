package com.sailing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;

import com.sailing.config.Config;
import com.sailing.model.FileNode;

public class LogCollector {
	private static Logger log = Logger.getLogger(LogCollector.class);

	private Config config;
	private Producer<byte[], byte[]> producer;
	private final Map<AsynchronousFileChannel, FileNode> map = new HashMap<AsynchronousFileChannel, FileNode>();
	private final long hour = 3600 * 1000;
	
	private LogCollector() {
	}

	private void load(DateTime dateTime) throws IOException {
		this.map.clear();
	 	int hour = dateTime.getHourOfDay();
	 	String date = dateTime.toString("yyyy-MM-dd");
	 	Path startingDir = Paths.get(config.basePath + "/" + date + "/" + hour);
	 	List<Path> result = new LinkedList<Path>();
	 	Files.walkFileTree(startingDir, new FindJavaVisitor(result));
	 	int index = 0;
		for (Path p : result) {
			AsynchronousFileChannel channel = AsynchronousFileChannel.open(p, StandardOpenOption.READ);
			FileNode node = new FileNode();
			node.setBf(ByteBuffer.allocate(100000));
			node.getBf().clear();
			node.setCnt(null);
			node.setOffset(config.fileoffset[index]);
			node.setCurTime(dateTime.getMillis());
			this.map.put(channel, node);
			index ++;
			log.info("load file successs:" + p.toAbsolutePath());
		}
		log.info("init successs!");
	}
	
	public void process() throws IOException,
			InterruptedException, ExecutionException, TimeoutException {
		while(true){
			int count = 0;
			while (true) {
				for (Entry<AsynchronousFileChannel, FileNode> entry : this.map.entrySet()) {
					Future<Integer> f = entry.getKey().read(entry.getValue().getBf(), entry.getValue().getOffset());
					entry.getValue().setCnt(f);
				}
	
				for (FileNode node : this.map.values()) {
					Integer cnt = node.getCnt().get();
					if (cnt > 0) {
						handle(node);
					}else if(!node.isHasReadEOF()){
						if(check(node.getCurTime())){
							count = count + 1;
							node.setHasReadEOF(true);
						}
					}
				}
				
				if(count == 10){
					for(AsynchronousFileChannel channel : this.map.keySet()){
						 channel.close();
					}
					break;
				}
			}
			if(!loadNext()){
				log.info("load next dir error!");
				break;
			}
		}
	}
	
	private boolean check(long curTime) {
		DateTime now = new DateTime();
		DateTime cur = new DateTime(curTime);
		if(now.getHourOfDay() == cur.getHourOfDay()){
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return false;
		}
		return checkFile(curTime + hour);
	}

	private boolean checkFile(long l) {
		DateTime dateTime = new DateTime(l);
		int hour = dateTime.getHourOfDay();
	 	String date = dateTime.toString("yyyy-MM-dd");
	 	Path startingDir = Paths.get(config.basePath + "/" + date + "/" + hour);
	 	boolean exist = Files.exists(startingDir, LinkOption.NOFOLLOW_LINKS);
	 	if(exist){
		 	try {
		 		List<Path> result = new LinkedList<Path>();
				Files.walkFileTree(startingDir, new FindJavaVisitor(result));
				if(result.size() == 10){
					return true;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		 	log.info("try next file: not exist!");
	 	}
		return false;
	}

	private boolean loadNext() {
		long curTime = 0; 
		for(FileNode node : map.values()){
			 curTime = node.getCurTime();
			 break;
		}
		curTime = curTime + hour;
		try {
			load(new DateTime(curTime));
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	private  void handle(FileNode node ) {		
		ByteBuffer bf = node.getBf();
		bf.flip();
		int limit = bf.limit();
		int index = 0;
		for(int i = 0; i < limit; i++){
			byte c = bf.get(i);
			if(c == '\n'){
				int length = (i + 1) - index;
				if(length != 0){
					byte[] dst = new byte[length - 1];
					bf.get(dst, 0, length - 1);
					bf.get();
					producer.send(new ProducerRecord<byte[], byte[]>(config.feed, dst));
				}
				index = i + 1;
			}
		}
		
		int finallength = limit - index;
		byte[] fdst = new byte[finallength];
		bf.get(fdst, 0, finallength);
		bf.clear();
		bf.put(fdst);
		node.setOffset(node.getOffset() + limit - node.getLastFinalLength());
		node.setLastFinalLength(finallength);
	}
	
	public void destroy(){
		this.producer.close();
	}

	public  class FindJavaVisitor extends SimpleFileVisitor<Path> {
		private List<Path> result;
		public FindJavaVisitor(List<Path> result) {
			this.result = result;
		}
		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
			if (file.toString().endsWith(config.suffix)) {
				result.add(file.toAbsolutePath());
			}
			return FileVisitResult.CONTINUE;
		}
	}

	public static LogCollector build(Config config) throws IOException {
		LogCollector lc = new LogCollector();
		lc.config = config;
		lc.producer = new KafkaProducer<byte[], byte[]>(config.kafkaProducerProps);
		lc.load(DateTime.parse(config.startTime));
		return lc;
	}
}
