package com.maxam;

import java.util.concurrent.CyclicBarrier;

/**
 * @author fanjinlong
 * @date 2019-02-22 17:11
 **/
public class CyclicBarrierTest {

	CyclicBarrier cyclicBarrier = new CyclicBarrier(1);

	public static void main(String[] args) {
		new Thread(() -> {
			for (int i = 0; i < 10; i++) {
				System.out.println(Thread.currentThread().getName()+"====" + i);
			}
		}).start();

		new Thread(() -> {
			for (int i = 0; i < 10; i++) {
				System.out.println(Thread.currentThread().getName()+"====" + i);
			}
		}).start();

		new Thread(() -> {
			for (int i = 0; i < 10; i++) {
				System.out.println(Thread.currentThread().getName()+"====" + i);
			}
		}).start();

		System.out.println("+=============");
	}

}
