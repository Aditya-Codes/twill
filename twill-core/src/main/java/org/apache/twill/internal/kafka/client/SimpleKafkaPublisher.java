/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.twill.internal.kafka.client;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;
import org.apache.twill.common.Cancellable;
import org.apache.twill.kafka.client.Compression;
import org.apache.twill.kafka.client.KafkaPublisher;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;

/**
 * Implementation of {@link KafkaPublisher} using the kafka scala-java api.
 */
final class SimpleKafkaPublisher implements KafkaPublisher {

  private final String kafkaBrokers;
  private final Ack ack;
  private final Compression compression;
  private Producer<Integer, ByteBuffer> producer;

  public SimpleKafkaPublisher(String kafkaBrokers, Ack ack, Compression compression) {
    this.kafkaBrokers = kafkaBrokers;
    this.ack = ack;
    this.compression = compression;
  }

  /**
   * Start the publisher. This method must be called before other methods. This method is only to be called
   * by KafkaClientService who own this object.
   * @return A Cancellable for closing this publish.
   */
  Cancellable start() {
    // It should return a Cancellable that is not holding any reference to this instance.
    // It is for ZKKafkaClientService to close the internal Kafka producer when this publisher get garbage collected.
    Properties props = new Properties();
    props.put("metadata.broker.list", kafkaBrokers);
    props.put("serializer.class", ByteBufferEncoder.class.getName());
    props.put("key.serializer.class", IntegerEncoder.class.getName());
    props.put("partitioner.class", IntegerPartitioner.class.getName());
    props.put("request.required.acks", Integer.toString(ack.getAck()));
    props.put("compression.codec", compression.getCodec());

    producer = new Producer<Integer, ByteBuffer>(new ProducerConfig(props));
    return new ProducerCancellable(producer);
  }

  @Override
  public Preparer prepare(String topic) {
    return new SimplePreparer(topic);
  }

  private final class SimplePreparer implements Preparer {

    private final String topic;
    private final List<KeyedMessage<Integer, ByteBuffer>> messages;

    private SimplePreparer(String topic) {
      this.topic = topic;
      this.messages = Lists.newLinkedList();
    }

    @Override
    public Preparer add(ByteBuffer message, Object partitionKey) {
      messages.add(new KeyedMessage<Integer, ByteBuffer>(topic, Math.abs(partitionKey.hashCode()), message));
      return this;
    }

    @Override
    public ListenableFuture<Integer> send() {
      int size = messages.size();
      producer.send(messages);

      messages.clear();
      return Futures.immediateFuture(size);
    }
  }

  private static final class ProducerCancellable implements Cancellable {
    private final Producer<Integer, ByteBuffer> producer;

    private ProducerCancellable(Producer<Integer, ByteBuffer> producer) {
      this.producer = producer;
    }

    @Override
    public void cancel() {
      producer.close();
    }
  }
}
