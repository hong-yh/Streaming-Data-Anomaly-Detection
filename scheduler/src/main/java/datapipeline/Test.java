package datapipeline;

import com.google.gson.Gson;
import datapipeline.common.Tuple2;
import datapipeline.common.Tuple2Serde;
import datapipeline.processors.ControlledFilterTransformer;
import datapipeline.streamer.ControlledFilterWrapper;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

public class Test {
    public static void main(String[] args) {

        final Properties streamsConfiguration = new Properties();
        final Serde<String> stringSerde = Serdes.String();
        final Serde<Double> doubleSerde = Serdes.Double();

        // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
        // against which the application is run.
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, KafkaConstants.STREAMER_PREFIX + 0);
        streamsConfiguration.put(StreamsConfig.CLIENT_ID_CONFIG, KafkaConstants.STREAMER_PREFIX + 0);

        KafkaManager km = KafkaManager.getInstance();
        KafkaManager.deleteTopicWithPrefix(KafkaConstants.STREAMER_PREFIX + 0);
        KafkaManager.deleteTopicWithPrefix("control_application_" + 0);
        KafkaManager.createTopic("control_application_" + 0, 1, (short) 1);

        // Where to find Kafka broker(s).
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaConstants.KAFKA_BROKERS);

        // Specify default (de)serializers for record keys and for record values.
        streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName()); // Set the commit interval to 500ms so that any changes are flushed frequently. The low latency
        streamsConfiguration.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);


        final StreamsBuilder builder = new StreamsBuilder();

        //final KStream<String, String> view_1 = builder.stream("Boolean_test_0");
        final KStream<String, String> view = builder.stream("test");


        ControlledFilterWrapper<String, String> wrapper = new ControlledFilterWrapper<>();

        KStream<String, String> mappedStream = view
                .flatMap((k, v) -> {
                    List<KeyValue<String, String>> result = new LinkedList<>();

                    Gson gson = new Gson();
                    int[] value = gson.fromJson(v, int[].class);

                    for (int i = 0; i < value.length; i++) {
                        result.add(new KeyValue<>(Integer.toString(i), Integer.toString(value[i])));
                        // KafkaManager.createTopic(job_id, this._ID,(short)1);
                    }
                    return result;
                });

        KGroupedStream<String, String> groupedStream = mappedStream.groupByKey(Grouped.with(stringSerde, stringSerde));
        TimeWindowedKStream<String, String> windowedStream = groupedStream.windowedBy(TimeWindows.of(Duration.ofMillis(100)).grace(Duration.ofMillis((long)(100 * 0.1))));
        KTable<Windowed<String>, Tuple2<Double, Double>> aggregatedStream = windowedStream
                .aggregate(
                        () -> new Tuple2<>(0.0, 0.0),
                        (aggKey, newValue, aggregate)  -> {
                            aggregate.value1 ++;
                            try{
                                aggregate.value2 += Integer.parseInt(newValue);
                            } catch(Exception e) {

                            }
                            return aggregate;
                        },
                        Materialized.<String, Tuple2<Double, Double>, WindowStore<Bytes, byte[]>>as("timed-window").withValueSerde(new Tuple2Serde<>())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        KStream<String, String> anomalousData = aggregatedStream
                .mapValues(value -> Double.toString(value.value2 / value.value1))
                .toStream()
                .map((key, value) -> new KeyValue<>(key.toString(), value));


        wrapper.getControlledStream(anomalousData
                , builder
                , KafkaConstants.CONTROL_FLOW_TOPIC_PREFIX + 0
                , new ControlledFilterTransformer<>(0, 8)
                ,0)
                .to("anomaly_0");


        final KafkaStreams streams = new KafkaStreams(builder.build(), streamsConfiguration);

        streams.cleanUp();
        streams.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        while (true) {
            try {
                Thread.sleep(10000000);
            } catch (Exception e) {

            }
        }
    }
}
