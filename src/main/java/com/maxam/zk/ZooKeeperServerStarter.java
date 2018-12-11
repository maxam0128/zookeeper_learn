package com.maxam.zk;

import org.apache.zookeeper.server.quorum.QuorumPeerMain;

/**
 * @author fanjinlong
 * @date 2018-10-23 15:51
 **/
public class ZooKeeperServerStarter {


	public static void main(String[] args) {
		String str = "C:\\Users\\hzfanjinlong\\IdeaProjects\\springlearning\\zookeeper\\src\\main\\resources\\zoo3.cfg";
		String [] arg1 = new String[]{str};
		QuorumPeerMain.main(arg1);
	}
}
