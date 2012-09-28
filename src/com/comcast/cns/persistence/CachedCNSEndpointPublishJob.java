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
package com.comcast.cns.persistence;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import org.apache.log4j.Logger;

import com.comcast.cmb.common.persistence.PersistenceFactory;
import com.comcast.cmb.common.util.CMBErrorCodes;
import com.comcast.cmb.common.util.CMBException;
import com.comcast.cmb.common.util.ExpiringCache;
import com.comcast.cmb.common.util.ExpiringCache.CacheFullException;
import com.comcast.cns.model.CNSEndpointPublishJob;
import com.comcast.cns.model.CNSMessage;
import com.comcast.cns.model.CNSSubscription;
import com.comcast.cns.model.CNSSubscription.CnsSubscriptionProtocol;
import com.comcast.cns.util.Util;

/**
 * This class supports caching of the subinfo in memory
 * The static cache uses topicArn as the key for the cache. The value is a concurrent-hashmap of subArn->subInfo
 * {{topicArn, exp}->{subArn->{subInfo}}}
 * 
 * @author aseem
 * 
 * Class is thread-safe
 */
public class CachedCNSEndpointPublishJob extends CNSEndpointPublishJob {
    private static Logger logger = Logger.getLogger(CachedCNSEndpointPublishJob.class);

    private static final ExpiringCache<String, LinkedHashMap<String, CachedSubInfo>> cache = new ExpiringCache<String, LinkedHashMap<String,CachedSubInfo>>(1000);
    
    /**
     * 
     * @param topicArn
     * @return THh list of sub-infos
     * @throws Exception 
     */
    public static List<CachedSubInfo> getSubInfos(String topicArn) throws Exception {
        LinkedHashMap<String, CachedSubInfo> arnToSubInfo;
        try {
            arnToSubInfo = cache.getAndSetIfNotPresent(topicArn, new CachePopulator(topicArn), 60000);
        } catch (CacheFullException e) {
            arnToSubInfo = new CachePopulator(topicArn).call();
        } catch(IllegalStateException e) {
            if ((e.getCause() instanceof ExecutionException) && 
                (e.getCause().getCause() instanceof TopicNotFoundException)) {
                throw new TopicNotFoundException("Topic Not Found:" + topicArn);
            } else {
                throw e;
            }
        }
        return new LinkedList<CachedSubInfo>(arnToSubInfo.values());
    }
    
    public static class CachedSubInfo extends SubInfo {

        public CachedSubInfo(CnsSubscriptionProtocol protocol, String endpoint, String subArn) {
            super(protocol, endpoint, subArn);
        }
        public CachedSubInfo(SubInfo info) {
            super(info.protocol, info.endpoint, info.subArn);
        }
        
        @Override
        public String serialize() {
            return subArn;
        }

        public static CachedSubInfo parseInstance(String str) {
            return new CachedSubInfo(SubInfo.parseInstance(str));
        }                
        /**
         * 
         * @param arr array of serialized CachedSubInfo objects (or its identifiers)
         * @param idx start index
         * @param count num to read
         * @return list of fully populated SubInfos in random order
         * @throws Exception 
         */
        public static List<CachedSubInfo> parseInstances(String topicArn, String []arr, int idx, int count, boolean useCache) throws Exception {
            if (count == 0) return Collections.emptyList();
            
            List<CachedSubInfo> infos = new LinkedList<CachedCNSEndpointPublishJob.CachedSubInfo>();
            if (useCache) {
                
                HashMap<String, CachedSubInfo> arnToSubInfo;
                try {
                    arnToSubInfo = cache.getAndSetIfNotPresent(topicArn, new CachePopulator(topicArn), 60000);
                } catch (CacheFullException e) {
                    arnToSubInfo = new CachePopulator(topicArn).call();
                } catch(IllegalStateException e) {
                    if ((e.getCause() instanceof ExecutionException) && 
                        (e.getCause().getCause() instanceof TopicNotFoundException)) {
                        throw new TopicNotFoundException("Topic Not Found:" + topicArn);
                    } else {
                        throw e;
                    }
                }
                
                for (int i = idx; i < idx + count ; i++) {
                    String subArn = arr[i];
                    CachedSubInfo subInfo = arnToSubInfo.get(subArn);
                    if (subInfo == null) {
                        logger.info("event=parseInstances info=subinfo_not_found arn=" + subArn);
                    } else {
                        infos.add(subInfo);
                    }
                }                
            } else {
                //get from Cassandra                
                ICNSSubscriptionPersistence pers = PersistenceFactory.getSubscriptionPersistence();
                for (int i = idx; i < idx + count ; i++) {
                    String subArn = arr[i];
                    CNSSubscription sub = pers.getSubscription(subArn);
                    if (sub == null) {
                        logger.info("event=parseInstances info=subinfo_not_found arn=" + subArn);
                    } else {
                        infos.add(new CachedSubInfo(sub.getProtocol(), sub.getEndpoint(), sub.getArn()));
                    }
                }                
            }
            return infos;
        }
    }
    
    public CachedCNSEndpointPublishJob(CNSMessage message, List<? extends SubInfo> subInfos) {
        super(message, subInfos);
    }
    
    
    /**
     * Class responsible for returning the contents of the cache if cache miss occurs
     */
    static class CachePopulator implements Callable<LinkedHashMap<String,CachedSubInfo>> {
        final String topicArn;
        public CachePopulator(String topicArn) {
            this.topicArn = topicArn;
        }
        @Override
        public LinkedHashMap<String, CachedSubInfo> call() throws Exception {
            long ts1 = System.currentTimeMillis();
            ICNSSubscriptionPersistence pers = PersistenceFactory.getSubscriptionPersistence();
            List<CNSSubscription> subs = pers.listSubscriptionsByTopic(null, topicArn, null, Integer.MAX_VALUE); //get all in one call
            LinkedHashMap<String, CachedSubInfo> val = new LinkedHashMap<String, CachedCNSEndpointPublishJob.CachedSubInfo>();
            for (CNSSubscription sub : subs) {
                if (!sub.getArn().equals("PendingConfirmation")) {
                    val.put(sub.getArn(), new CachedSubInfo(sub.getProtocol(), sub.getEndpoint(), sub.getArn()));
                }
            }
            long ts2 = System.currentTimeMillis();
            logger.info("event=CachePopulator topicArn=" + topicArn + " responseTimeMS=" + (ts2 - ts1));
            return val;
        }        
    }
    
    /**
     * Construct a CNSEndpointPublishJob given its string representation
     * @param str
     * @return
     * @throws CMBException
     */
    public static CNSEndpointPublishJob parseInstance(String str) throws CMBException {
        String arr[] = str.split("\n");
        if (arr.length < 2) {
            throw new IllegalArgumentException("Expected at least two tokens in CNSEndpointPublishJob serial representation. Expect4ed <num-subinfos>\n[<sub-info>\n<sub-info>...\n]<CNSPublishJob>. Got:" + str);
        }
        int numSubInfos = Integer.parseInt(arr[0]);   
        int idx = 1;
        
        //Note: We assume that topic-ARN is part of the sub-arn
        
        String topicArn = null;
        boolean useCache = true;
        if (numSubInfos > 0) {
            topicArn = Util.getCnsTopicArn(arr[idx]);
        }

        List<CachedSubInfo> subInfos;
        try {
            subInfos = CachedSubInfo.parseInstances(topicArn, arr, idx, numSubInfos, useCache);
        } catch(TopicNotFoundException e) {
            logger.error("event=parseInstance status=TopicNotFound topicArn=" + topicArn);
            throw e;
        } catch (Exception e) {
            logger.error("event=parseInstance status=exception", e);
            throw new CMBException(CMBErrorCodes.InternalError, e.getMessage());
        }

        idx += numSubInfos;
        
        StringBuffer sb = new StringBuffer();
        for (int j = idx; j < arr.length; j++) {
            if (j != idx) {
                sb.append("\n");
            }
            sb.append(arr[j]);
        }
        
        CNSMessage message = null;
        try {
            message = CNSMessage.parseInstance(sb.toString());
        } catch(Exception e) {
            logger.error("event=parseInstance status=failed cnsmessage-serialized=" + sb.toString(), e);
            throw new CMBException(CMBErrorCodes.InternalError, e.getMessage());
        }
        message.processMessageToProtocols();
        
        return new CachedCNSEndpointPublishJob(message, subInfos);
    }

}
