/*
 *  The MIT License
 *
 *  Copyright 2015 Sony Mobile Communications Inc. All rights reserved.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package com.sonymobile.jenkins.plugins.mq.mqnotifier;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import com.squareup.tape.QueueFile;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;

/**
 * Creates an MQ connection.
 *
 * @author Örjan Percy &lt;orjan.percy@sonymobile.com&gt;
 */
public final class MQConnection implements ShutdownListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(MQConnection.class);
    private static final int HEARTBEAT_INTERVAL = 30;
    private static final int CONNECTION_WAIT = 10000;

    private String userName;
    private Secret userPassword;
    private String serverUri;
    private String virtualHost;
    private Connection connection = null;
    private Channel channel = null;

    private static Thread messageQueueThread;

    private static QueueFile queueFile;

    private static MQNotifierConfig config;
    /**
     * Lazy-loaded singleton using the initialization-on-demand holder pattern.
     */
    private MQConnection() { }

    /**
     * Is only executed on {@link #getInstance()} invocation.
     */
    private static class LazyRabbit {
        private static final MQConnection INSTANCE = new MQConnection();
        private static final ConnectionFactory CF = new ConnectionFactory();
    }

    /**
     * Gets the instance.
     *
     * @return the instance
     */
    public static MQConnection getInstance() {
        return LazyRabbit.INSTANCE;
    }

    /**
     * Stores data for a RabbitMQ message.
     */
    private static final class MessageData {
        private String exchange;
        private String routingKey;
        private AMQP.BasicProperties props;
        private byte[] body;

        /**
         * Constructor.
         *
         * @param exchange the exchange to publish the message to
         * @param routingKey the routing key
         * @param props other properties for the message - routing headers etc
         * @param body the message body
         */
        private MessageData(String exchange, String routingKey, AMQP.BasicProperties props, byte[] body) {
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.props = props;
            this.body = body;
        }

        /**
         * Gets the exchange name.
         *
         * @return the exchange name
         */
        private String getExchange() {
            return exchange;
        }

        /**
         * Gets the routing key.
         *
         * @return the routing key
         */
        private String getRoutingKey() {
            return routingKey;
        }

        /**
         * Gets the connection properties.
         *
         * @return the connection properties
         */
        private AMQP.BasicProperties getProps() {
            return props;
        }

        /**
         * Gets the message body.
         *
         * @return the message body
         */
        private byte[] getBody() {
            return body;
        }
    }

    /**
     * Puts a message in the message queue.
     *
     * @param exchange the exchange to publish the message to
     * @param routingKey the routing key
     * @param props other properties for the message - routing headers etc
     * @param body the message body
     */
    public void addMessageToQueue(String exchange, String routingKey, AMQP.BasicProperties props, byte[] body) {
        if (queueFile == null) {
            Jenkins jenkins = Jenkins.get();
            String filePath = new String(jenkins.getRootDir().toString() + "/build.db");

            File file = new File(filePath);

            try {
                queueFile = new QueueFile(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (messageQueueThread == null || !messageQueueThread.isAlive()) {
            messageQueueThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    sendMessages();
                }
            });
            messageQueueThread.start();
            LOGGER.info("messageQueueThread recreated since it was null or not alive.");
        }

        int count = 0;
        while (true) {
            try {
                queueFile.add(body);
                break;
            } catch (IOException e) {
                LOGGER.error("add message to queue error");
                LOGGER.error(e.getMessage());
                count += 1;
            }
            if (count == 5) {
                LOGGER.error("fail to add item to queue after 5 times to try");
                break;
            }
        }
    }

    /**
     * Sends messages from the message queue.
     */
    private void sendMessages() {
        if (config == null) {
            config = MQNotifierConfig.getInstance();
        }
        AMQP.BasicProperties.Builder bob = null;
        if (config != null && config.isNotifierEnabled()) {
            bob = new AMQP.BasicProperties.Builder();
            int dm = 1;
            if (config.getPersistentDelivery()) {
                dm = 2;
            }
            bob.appId(config.getAppId());
            bob.deliveryMode(dm);
            bob.contentType(Util.CONTENT_TYPE);
            bob.timestamp(Calendar.getInstance().getTime());
        }
        while (true) {
            try {
                byte[] data = queueFile.peek();
                boolean is_ok = false;
                if (data != null) {
                    LOGGER.info("send message");
                    is_ok =getInstance().send(config.getExchangeName(), config.getRoutingKey(),
                            bob.build() , data);
                }
                if (is_ok) {
                    LOGGER.info("remove message");
                    queueFile.remove();
                }
            } catch (IOException ie) {
                LOGGER.info("get message from account an error: ", ie);
            }
        }
    }

    /**
     * Gets the connection factory that will enable a connection to the AMQP server.
     *
     * @return the connection factory
     */
    private ConnectionFactory getConnectionFactory() {
        if (LazyRabbit.CF != null) {
            try {
                // Try to recover the topology along with the connection.
                LazyRabbit.CF.setAutomaticRecoveryEnabled(true);
                // set requested heartbeat interval, in seconds
                LazyRabbit.CF.setRequestedHeartbeat(HEARTBEAT_INTERVAL);
                LazyRabbit.CF.setUri(serverUri);
                if (StringUtils.isNotEmpty(virtualHost)) {
                    LazyRabbit.CF.setVirtualHost(virtualHost);
                }
            } catch (KeyManagementException e) {
                LOGGER.error("KeyManagementException: ", e);
            } catch (NoSuchAlgorithmException e) {
                LOGGER.error("NoSuchAlgorithmException: ", e);
            } catch (URISyntaxException e) {
                LOGGER.error("URISyntaxException: ", e);
            }
            if (StringUtils.isNotEmpty(userName)) {
                LazyRabbit.CF.setUsername(userName);
                if (StringUtils.isNotEmpty(Secret.toString(userPassword))) {
                    LazyRabbit.CF.setPassword(Secret.toString(userPassword));
                }
            }
        }
        return LazyRabbit.CF;
    }

    /**
     * Gets the connection.
     *
     * @return the connection.
     */
    public Connection getConnection() {
        if (connection == null) {
            try {
                connection = getConnectionFactory().newConnection();
                connection.addShutdownListener(this);
            } catch (IOException e) {
                LOGGER.warn("Connection refused", e);
            }
        }
        return connection;
    }

    /**
     * Initializes this instance with supplied values.
     *
     * @param name the user name
     * @param password the user password
     * @param uri the server uri
     * @param vh the virtual host
     */
    public void initialize(String name, Secret password, String uri, String vh) {
        userName = name;
        userPassword = password;
        serverUri = uri;
        virtualHost = vh;
        connection = null;
        channel = null;
    }

    /**
     * Sends a message.
     * Keeps trying to get a connection indefinitely.
     *
     * @param exchange the exchange to publish the message to
     * @param routingKey the routing key
     * @param props other properties for the message - routing headers etc
     * @param body the message body
     */
    private boolean send(String exchange, String routingKey, AMQP.BasicProperties props, byte[] body) {
        if (exchange == null) {
            LOGGER.error("Invalid configuration, exchange must not be null.");
            return false;
        }

        while (true) {
            try {
                if (channel == null || !channel.isOpen()) {
                    connection = getConnection();
                    if (connection != null) {
                        channel = connection.createChannel();
                        if (!getConnection().getAddress().isLoopbackAddress()) {
                            channel.exchangeDeclarePassive(exchange);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Cannot create channel", e);
                channel = null; // reset
            } catch (ShutdownSignalException e) {
                LOGGER.error("Cannot create channel", e);
                channel = null; // reset
                return false;
            }
            if (channel != null) {
                try {
                    channel.basicPublish(exchange, routingKey, props, body);
                } catch (IOException e) {
                    LOGGER.error("Cannot publish message", e);
                    return false;
                } catch (AlreadyClosedException e) {
                    LOGGER.error("Connection is already closed", e);
                    return false;
                }
                return true;
            } else {
                try {
                    Thread.sleep(CONNECTION_WAIT);
                } catch (InterruptedException ie) {
                    LOGGER.error("Thread.sleep() was interrupted", ie);
                }
            }
        }
    }

    @Override
    public void shutdownCompleted(ShutdownSignalException cause) {
        if (cause.isHardError()) {
            if (!cause.isInitiatedByApplication()) {
                LOGGER.warn("MQ connection was suddenly disconnected.");
                try {
                    if (connection != null && connection.isOpen()) {
                        connection.close();
                    }
                    if (channel != null && channel.isOpen()) {
                        channel.close();
                    }
                } catch (IOException e) {
                    LOGGER.error("IOException: ", e);
                } catch (AlreadyClosedException e) {
                    LOGGER.error("AlreadyClosedException: ", e);
                } finally {
                    channel = null;
                    connection = null;
                }
            }
        } else {
            LOGGER.warn("MQ channel was suddenly disconnected.");
        }
    }
}
