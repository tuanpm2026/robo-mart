package com.robomart.notification.kafka;

import java.nio.ByteBuffer;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Retain arbitrary key bytes, including null and non-UTF8 keys, for exact replay. */
public class RawKeyDeserializer extends StringDeserializer {
    public static final String RAW_KEY = "x-robomart-original-key";

    @Override
    public String deserialize(String topic, Headers headers, byte[] value) {
        headers.remove(RAW_KEY);
        headers.add(RAW_KEY, value);
        return super.deserialize(topic, value);
    }
    @Override
    public String deserialize(String topic, Headers headers, ByteBuffer value) {
        byte[] bytes = null;
        if (value != null) {
            ByteBuffer copy = value.duplicate();
            bytes = new byte[copy.remaining()];
            copy.get(bytes);
        }
        return deserialize(topic, headers, bytes);
    }

}
