package com.maxam;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author fanjinlong
 * @date 2019-02-21 16:50
 **/
public class LockOddEvenNumber {

	static volatile int count = 0;
	public static void main(String[] args) {

		final ReentrantLock lock = new ReentrantLock();
		final Condition condition = lock.newCondition();
		final Condition condition2 = lock.newCondition();

		new Thread(new Runnable() {
			@Override
			public void run() {
				while (count < 100) {
					lock.lock();
					try {
						if(count %2 == 0){
							condition.await();
						}
						System.out.println("thread:" + Thread.currentThread().getId() + "**********" + count);
						count++;
						condition2.signal();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} finally {
						lock.unlock();
					}
				}
			}
		}).start();

		new Thread(new Runnable() {
			@Override
			public void run() {
				while (count < 100){
					lock.lock();
					try {
						if(count % 2 != 0){
							condition2.await();
						}
						System.out.println("thread:" + Thread.currentThread().getId()+"======"+ count);
						count++;
						condition.signal();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}finally {
						lock.unlock();
					}
				}
			}
		}).start();
	}

	public static class OddThread implements Runnable{

		private final LockHold lock;

		public OddThread(LockHold lock) {
			this.lock = lock;
		}

		@Override
		public void run() {
			while (lock.getI() <= 100){
					lock.getLock().lock();
					if(lock.getI() %2 != 0){
						try {
							lock.getCondition2().await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}else {
						System.out.println("thread:" + Thread.currentThread().getId()+"======"+lock.getI());
						lock.setI(lock.getI()+1);
						lock.getCondition1().signal();
					}
				}
			}
	}

	public static class EvenThread implements Runnable{

		private final LockHold lock;

		public EvenThread(LockHold lock) {
			this.lock = lock;
		}

		@Override
		public void run() {
			while (lock.getI() <= 100){
				synchronized (lock){
					if(lock.getI() % 2 == 0){
						try {
							lock.getCondition1().await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}else {
						System.out.println("thread:" + Thread.currentThread().getId()+"********"+lock.getI());
						lock.setI(lock.getI()+1);
						lock.getCondition2().signal();
					}
				}
			}
		}
	}

	public static class LockHold{
		private ReentrantLock lock;
		private Condition condition1;
		private Condition condition2;
		private int i;

		public ReentrantLock getLock() {
			return lock;
		}

		public void setLock(ReentrantLock lock) {
			this.lock = lock;
		}

		public Condition getCondition1() {
			return condition1;
		}

		public void setCondition1(Condition condition1) {
			this.condition1 = condition1;
		}

		public Condition getCondition2() {
			return condition2;
		}

		public void setCondition2(Condition condition2) {
			this.condition2 = condition2;
		}

		public int getI() {
			return i;
		}

		public void setI(int i) {
			this.i = i;
		}
	}
}
