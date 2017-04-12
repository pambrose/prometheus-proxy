package com.sudothought;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Test {

  public static void main(String[] args) {

    final BlockingQueue<String> queue = new ArrayBlockingQueue<>(1000);

    final ExecutorService executorService = Executors.newFixedThreadPool(2);

    executorService.submit(() -> {
      for (int i = 0; i < 10; i++) {
        try {
          Thread.sleep(1000);
          queue.put("Value: " + i);
        }
        catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    });
    executorService.submit(() -> {
      while (true) {
        System.out.println(queue.take());
      }
    });

  }
}
