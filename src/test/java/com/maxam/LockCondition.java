package com.maxam;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author fanjinlong
 * @date 2019-02-22 14:47
 **/
public class LockCondition {

	public static void main(String[] args) {
		ReentrantLock lock = new ReentrantLock();

		for (int i = 0; i < 10; i++) {
			new Thread(() -> {
				lock.lock();
				System.out.println(Thread.currentThread().getId());
			}).start();
		}

		int consumer = 5;
		int producer = 3;
		Condition pCondition = lock.newCondition();
		Condition cCondition = lock.newCondition();
		Queue<String> queue = new ArrayDeque(10);

		for (int i = 0; i < producer; i++) {
			new Thread(() -> {
				while (true){
					lock.lock();
					try {
						Thread.sleep(new Random().nextInt(1000));
						if(queue.size() == 100){
							pCondition.await();
						}
						cCondition.signalAll();
						System.out.println("======"+Thread.currentThread().getId());
						queue.add(Thread.currentThread().getId()+"");
					} catch (InterruptedException e) {
						e.printStackTrace();
					}finally {
						lock.unlock();
					}
				}
			}).start();
		}

		for (int i = 0; i < consumer; i++) {
			new Thread(() -> {
				while (true){
					lock.lock();
					try {
						Thread.sleep(new Random().nextInt(800));
						if(queue.size() == 0){
							cCondition.await();
						}
						pCondition.signalAll();
						System.out.println(Thread.currentThread().getId()+"===="+queue.poll());
					} catch (InterruptedException e) {
						e.printStackTrace();
					} finally {
						lock.unlock();
					}
				}
			}).start();
		}

	}
}
