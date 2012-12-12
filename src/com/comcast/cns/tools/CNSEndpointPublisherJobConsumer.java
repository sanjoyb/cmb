/**
 * Copyright 2012 Comcast Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.comcast.cns.tools;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.prettyprint.hector.api.HConsistencyLevel;

import org.apache.http.conn.HttpHostConnectException;
import org.apache.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.Message;
import com.comcast.cmb.common.controller.CMBControllerServlet;
import com.comcast.cmb.common.model.User;
import com.comcast.cmb.common.persistence.PersistenceFactory;
import com.comcast.cmb.common.util.CMBProperties;
import com.comcast.cmb.common.util.PersistenceException;
import com.comcast.cmb.common.util.ValueAccumulator.AccumulatorName;
import com.comcast.cns.controller.CNSMonitor;
import com.comcast.cns.model.CNSEndpointPublishJob;
import com.comcast.cns.model.CNSEndpointPublishJob.CNSEndpointSubscriptionInfo;
import com.comcast.cns.persistence.CNSCachedEndpointPublishJob;
import com.comcast.cns.persistence.TopicNotFoundException;

/**
 * 
 * This is the Endpoint Job Consumer. Its role is to dequeue CNSEndpointPublishJobs from
 * CQS, send the notifications and retry if needed
 * This class uses two internal executors to send notifications: one for normal notifications and one for retrys
 * @author aseem, bwolf
 * 
 * Class is thread-safe
 */

public class CNSEndpointPublisherJobConsumer implements CNSPublisherPartitionRunnable {
	
    private static Logger logger = Logger.getLogger(CNSEndpointPublisherJobConsumer.class);
    
    private static final String CNS_CONSUMER_QUEUE_NAME_PREFIX = CMBProperties.getInstance().getCnsEndpointPublishQueueNamePrefix();

    private static volatile ScheduledThreadPoolExecutor deliveryHandlers = null;
    private static volatile ScheduledThreadPoolExecutor reDeliveryHandlers = null;
    private static volatile boolean initialized = false; 
    
    private static volatile Integer testQueueLimit = null;
            
    public static class MonitoringInterface {
    	
        public static int getDeliveryHandlersQueueSize() {
            return deliveryHandlers.getQueue().size();
        }
        
        public static int getReDeliveryHandlersQueueSize() {
            return reDeliveryHandlers.getQueue().size();
        }
    }
    
    public static void submitForReDeliver(CNSPublishJob job, long delay, TimeUnit unit) {
    	reDeliveryHandlers.schedule(job, delay, unit);
    }
    
    public static class TestInterface {
        public static boolean isInitialized() {
            return initialized;
        }
        public static AmazonSQS getSQS() {
            return CQSHandler.getSQSHandler();
        }
        public static void setAmazonSQS(AmazonSQS sqs) {
            CQSHandler.setSQSHandler(sqs);
        }
        public static void setTestQueueLimit(Integer limit) {
            testQueueLimit = limit;
        }
        public static void clearDeliveryHandlerQueue() {
            deliveryHandlers.getQueue().clear();
        }
        public static void clearReDeliveryHandlerQueue() {
            reDeliveryHandlers.getQueue().clear();
        }
    }
    
    /**
     * Make the appr executors
     * Read the deliveryNumHandlers and the retryDeliveryNumHandlers properties at startup
     * Read the EndpointPublishQ_<m> property and ensuring they exist (create if not) 
     * @throws PersistenceException 
     */
    public static void initialize() throws PersistenceException {
    	
    	if (initialized) {
    		return;
    	}

    	deliveryHandlers = new ScheduledThreadPoolExecutor(CMBProperties.getInstance().getNumDeliveryHandlers());
    	reDeliveryHandlers = new ScheduledThreadPoolExecutor(CMBProperties.getInstance().getNumReDeliveryHandlers());

    	CQSHandler.initialize();

    	CQSHandler.ensureQueuesExist(CNS_CONSUMER_QUEUE_NAME_PREFIX, CMBProperties.getInstance().getNumEPPublishJobQs());

    	logger.info("event=initialize status=success");
    	initialized = true;
    }
    
    /**
     * shutsdown all internal thread-pools. Cannot use object again unless initialize() called again.
     */
    public static void shutdown() {
        deliveryHandlers.shutdownNow();
        reDeliveryHandlers.shutdownNow();
        initialized = false;
        CQSHandler.shutdown();
        logger.info("event=shutdown status=success");
    }
    
    /**
     * Server is overloaded if 
     *  - the size of queue for deliveryHandlers is larger than deliveryHandlerQLimit OR
     *  - The size of the reDeliveryHandlers is larger than reDeliveryHandlerQLimit
     * @return true if overloaded
     */
    public static boolean isOverloaded() {

    	if (testQueueLimit == null) {
        
    		if (deliveryHandlers.getQueue().size() >= CMBProperties.getInstance().getDeliveryHandlerJobQueueLimit() ||
                    reDeliveryHandlers.getQueue().size() >= CMBProperties.getInstance().getReDeliveryHandlerJobQueueLimit()) {
                return true;
            }
            
    		return false;
        
    	} else {
            logger.debug("event=is_overloaded queue_size=" + deliveryHandlers.getQueue().size());
        
            if (deliveryHandlers.getQueue().size() >= testQueueLimit || reDeliveryHandlers.getQueue().size() >= testQueueLimit) {
                return true;
            }
            
            return false;
        }
    }
            
    /**
     * a. Check if server is overloaded, if it is, go to sleep and retry
     * c. Start polling the EndpointPublishQ_<m> queues in a round-robin manner, getting the list of endpoints and handing them to the delivery executor framework. 
     * d. Create another executor called the reDeliveryExecutor that gets the failed endpoints from ( c). 
     * e. Keep in-memory structure that maps all individual executor jobs to the main EndpointPublish job. Once all individual jobs have completed, delete the EndpointPublish Job.
     * f. Set HTTP and TCP handshake timeouts
     * 
     * Note: the method backs off exponentially by putting the thread to sleep.
     * @return true if messages were found in current partition, false otherwise
     */
    @Override
    public boolean run(int partition) {
    	
        boolean messageFound = false;
        long ts0 = System.currentTimeMillis();
        CMBControllerServlet.valueAccumulator.initializeAllCounters();
        
        if (!initialized) {
            throw new IllegalStateException("Not initialized");
        }
        
        try {
        	
            long ts1 = System.currentTimeMillis();

            if (CNSPublisher.lastConsumerMinute.compareAndSet(ts1/(1000*60)-1, ts1/(1000*60))) {

            	String hostAddress = InetAddress.getLocalHost().getHostAddress();
                logger.info("event=ping version=" + CMBControllerServlet.VERSION + " ip=" + hostAddress);

	        	try {
		        	Map<String, String> values = new HashMap<String, String>();
		        	values.put("consumerTimestamp", System.currentTimeMillis() + "");
		        	values.put("jmxport", System.getProperty("com.sun.management.jmxremote.port", "0"));
		        	values.put("mode", CNSPublisher.getModeString());
	                CNSPublisher.cassandraHandler.insertOrUpdateRow(hostAddress, "CNSWorkers", values, HConsistencyLevel.QUORUM);
	        	} catch (Exception ex) {
	        		logger.warn("event=ping_glitch", ex);
	        	}
            }

	        if (isOverloaded()) {
            	
                logger.info("event=run status=server_overloaded");

                try {
                    Thread.sleep(100);                
                } catch (InterruptedException e) {
                    logger.error("event=run status=error", e);
                }

                CMBControllerServlet.valueAccumulator.deleteAllCounters();
                return messageFound;
            }
	        
	        String queueName = CNS_CONSUMER_QUEUE_NAME_PREFIX + partition;
	        String queueUrl = CQSHandler.getQueueUrl(queueName);
	        Message msg = CQSHandler.receiveMessage(queueUrl);
    		CNSMonitor.getInstance().registerCQSServiceAvailable(true);

            if (msg != null) {   
            	
                messageFound = true;
                
                try {
                	
                    CNSEndpointPublishJob endpointPublishJob = (CMBProperties.getInstance().isUseSubInfoCache()) ? CNSCachedEndpointPublishJob.parseInstance(msg.getBody()) : CNSEndpointPublishJob.parseInstance(msg.getBody());
                    logger.debug("endpoint_publish_job=" + endpointPublishJob.toString());
                    User pubUser = PersistenceFactory.getUserPersistence().getUserById(endpointPublishJob.getMessage().getUserId());
                    List<? extends CNSEndpointSubscriptionInfo> subs = endpointPublishJob.getSubInfos();
                    
                    CNSMonitor.getInstance().registerSendsRemaining(endpointPublishJob.getMessage().getMessageId(), subs.size());
                    
                    AtomicInteger endpointPublishJobCount = new AtomicInteger(subs.size());                
                    
                    for (CNSEndpointSubscriptionInfo sub : subs) {             
                        CNSPublishJob pubjob = new CNSPublishJob(endpointPublishJob.getMessage(), pubUser, sub.protocol, sub.endpoint, sub.subArn, queueUrl, msg.getReceiptHandle(), endpointPublishJobCount);
                        deliveryHandlers.submit(pubjob);
                    }
                    
                } catch (TopicNotFoundException e) {
                    logger.error("event=run_exception exception=TopicNotFound action=skip_job");
                    CQSHandler.deleteMessage(queueUrl, msg.getReceiptHandle());
                } catch (Exception e) {
                    logger.error("event=run_exception action=wait_for_revisibility", e);
                }
                
                long tsFinal = System.currentTimeMillis();
                logger.info("event=run_pass_done CNSCQSTimeMS=" + CMBControllerServlet.valueAccumulator.getCounter(AccumulatorName.CNSCQSTime) + " responseTimeMS=" + (tsFinal - ts0));
                
            } else {
                logger.debug("event=run_pass_done");
            }

        } catch (AmazonClientException ex) {

	    	if (ex.getCause() instanceof HttpHostConnectException) {
	    		logger.error("event=cqs_service_unavailable", ex);
	    		CNSMonitor.getInstance().registerCQSServiceAvailable(false);
	    	} else {
	        	CQSHandler.ensureQueuesExist(CNS_CONSUMER_QUEUE_NAME_PREFIX, CMBProperties.getInstance().getNumEPPublishJobQs());
	    	}

        } catch (Exception ex) {
            logger.error("event=run status=exception", ex);
        }
        
        CMBControllerServlet.valueAccumulator.deleteAllCounters();
        return messageFound;
    }    
}
