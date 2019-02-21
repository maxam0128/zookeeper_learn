package com.maxam;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author fanjinlong
 * @date 2019-02-21 16:50
 **/
public class LockOddEvenNumber {

	public static void main(String[] args) {

		Lock lock = new ReentrantLock();
		Condition c0 = lock.newCondition();
		Condition c1 = lock.newCondition();
		Condition c2 = lock.newCondition();




	}


	public static class LockHold{
		private Lock lock;
		private int i;
	}
}
