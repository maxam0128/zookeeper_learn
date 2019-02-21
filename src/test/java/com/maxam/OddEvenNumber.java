package com.maxam;

/**
 * @author fanjinlong
 * @date 2019-02-21 15:33
 **/
public class OddEvenNumber {

	public static void main(String[] args) {
		LockHolder lockHolder = new LockHolder(false,0);
		OddThread oddThread = new OddThread(lockHolder);
		EvenThread evenThread = new EvenThread(lockHolder);
		new Thread(oddThread).start();
		new Thread(evenThread).start();
	}




	public static class OddThread implements Runnable{

		private final LockHolder lock;

		public OddThread(LockHolder lock) {
			this.lock = lock;
		}

		@Override
		public void run() {
			while (lock.getI() <= 100){
				synchronized (lock){
					if(!lock.isFlag()){
						try {
							lock.wait();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}else {
						System.out.println("thread:" + Thread.currentThread().getId()+"======"+lock.getI());
						lock.setI(lock.getI()+1);
						lock.setFlag(false);
						lock.notify();
					}
				}
			}
		}
	}

	public static class EvenThread implements Runnable{

		private final LockHolder lock;

		public EvenThread(LockHolder lock) {
			this.lock = lock;
		}

		@Override
		public void run() {
			while (lock.getI() <= 100){
				synchronized (lock){
					if(lock.isFlag()){
						try {
							lock.wait();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}else {
						System.out.println("thread:" + Thread.currentThread().getId()+"********"+lock.getI());
						lock.setI(lock.getI()+1);
						lock.setFlag(true);
						lock.notify();
					}
				}
			}
		}
	}

	public static class LockHolder{

		private boolean flag;
		private int i;

		public LockHolder(boolean flag, int i) {
			this.flag = flag;
			this.i = i;
		}

		public boolean isFlag() {
			return flag;
		}

		public void setFlag(boolean flag) {
			this.flag = flag;
		}

		public int getI() {
			return i;
		}

		public void setI(int i) {
			this.i = i;
		}
	}


}
