package org.nd4j.parameterserver.distributed.transport;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.util.ArrayUtil;
import org.nd4j.parameterserver.distributed.conf.Configuration;
import org.nd4j.parameterserver.distributed.enums.NodeRole;

/**
 * Transport implementation based on Aeron UDP multicast
 *
 * PLEASE NOTE: This transport will NOT work on AWS or Azure out of box, due to Amazon/Microsoft restrictions within their networks.
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class MulticastTransport implements Transport {

    private Configuration configuration;
    private NodeRole nodeRole;

    private Aeron aeron;
    private Aeron.Context context;

    private String multicastChannelUri;
    private String unicastChannelUri;

    private String ip;

    // TODO: move this to singleton holder
    private MediaDriver driver;

    private Publication publicationForShards;
    private Publication publicationForClients;

    private Subscription subscriptionForShards;
    private Subscription subscriptionForClients;

    public MulticastTransport() {
        // no-op
    }

    @Override
    public void init(@NonNull Configuration configuration, @NonNull NodeRole role, @NonNull String localIp) {
        this.configuration = configuration;
        this.nodeRole = role;

        context = new Aeron.Context();

        driver = MediaDriver.launch();

        aeron = Aeron.connect(context);

        ip = localIp;

        if (configuration.getMulticastNetwork() == null || configuration.getMulticastNetwork().isEmpty())
            throw new ND4JIllegalStateException("For MulticastTransport you should provide IP from multicast network available/allowed in your environment, i.e.: 224.0.1.1");

        multicastChannelUri = "aeron:udp?endpoint=" + configuration.getMulticastNetwork() + ":" + configuration.getPort();
        if (configuration.getMulticastInterface() != null && !configuration.getMulticastInterface().isEmpty())
            multicastChannelUri =  multicastChannelUri + "|interface=" + configuration.getMulticastInterface();


        switch (nodeRole) {
            case SHARD:
                /*
                    In case of Shard, unicast address for communication is known in advance
                 */
                unicastChannelUri = "aeron:udp?endpoint=" + localIp + ":" + configuration.getPort();

                // this channel will be used to receive batches from Clients
                subscriptionForShards = aeron.addSubscription(unicastChannelUri, configuration.getStreamId());

                // this channel will be used to send completion reports back to Clients
                publicationForClients = aeron.addPublication(multicastChannelUri, configuration.getStreamId()+1);

                // this channel will be used for communication with other Shards
                publicationForShards = aeron.addPublication(multicastChannelUri, configuration.getStreamId() + 2);

                // this channel will be used to receive messages from other Shards
                subscriptionForClients = aeron.addSubscription(multicastChannelUri, configuration.getStreamId() + 2);
                break;
            case CLIENT:

                /*
                    In case of Client, unicast will be one of shards, picked up with random
                 */
                unicastChannelUri = "aeron:udp?endpoint=" + ArrayUtil.getRandomElement(configuration.getShardAddresses()) + ":" + configuration.getPort();

                /*
                 this channel will be used to send batches to Shards, it's 1:1 channel to one of the Shards
                */
                publicationForShards = aeron.addPublication(unicastChannelUri, configuration.getStreamId());

                // this channel will be used to receive completion reports from Shards
                subscriptionForClients = aeron.addSubscription(multicastChannelUri, configuration.getStreamId() + 1);
                break;
            default:
                log.warn("Unknown role passed: {}", nodeRole);
                throw new RuntimeException();
        }
    }


}
