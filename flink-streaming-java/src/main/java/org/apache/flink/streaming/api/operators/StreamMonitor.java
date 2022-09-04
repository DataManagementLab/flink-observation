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

package org.apache.flink.streaming.api.operators;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.java.operators.translation.WrappingFunction;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.streaming.runtime.operators.windowing.WindowOperator;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.internal.connection.ServerAddressHelper;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class is entirely new and added to the storm sources. The StreamMonitor is attached to the
 * processor nodes that are used in the Stream API. It is called for every incoming and outgoing
 * event and keeps track of the data characteristics (like tuple width, selectivities, etc.) As no
 * shutdown hooks can be reveiced here when shutting down the topology, the measurement has a
 * defined length It starts when the first tuple arrives. At the end, the data characteristics are
 * written into the logs per operator.
 */
public class StreamMonitor<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final HashMap<String, Object> description;
    private boolean initialized;
    private boolean observationMade;
    private long startTime;
    private int inputCounter;
    private int outputCounter;
    private final long duration = 30_000_000_000L; // 30 seconds, starting after first call
    private boolean localMode;
    private boolean distributedLogging;
    private final T operator;
    private final ArrayList<Double> joinSelectivities;
    private final ArrayList<Integer> windowLengths;
    private final Logger logger;
    private MongoCollection<JSONObject> mongoCollection;
    private boolean disableStreamMonitor;

    public StreamMonitor(HashMap<String, Object> description, T operator) {
        this.description = Objects.requireNonNullElseGet(description, HashMap::new);
        this.disableStreamMonitor = false;
        this.logger = LoggerFactory.getLogger("observation");
        this.description.put("tupleWidthIn", -1);
        this.description.put("tupleWidthOut", -1);
        this.initialized = false;
        this.observationMade = false;
        this.operator = operator;
        this.joinSelectivities = new ArrayList<>();
        this.windowLengths = new ArrayList<>();
        if (this.operator instanceof StreamFilter
                || this.operator instanceof WindowOperator
                || this.operator instanceof WrappingFunction) {
            this.description.put("realSelectivity", 0.0);
        }
    }

    public <T> void reportInput(T input, ExecutionConfig config) {
        if (this.disableStreamMonitor) {
            return;
        }
        if (!initialized) {
            initialized = true;
            Map<String, String> globalJobParametersMap = config.getGlobalJobParameters().toMap();
            // initialize loggers
            if (globalJobParametersMap.get("-distributedLogging").equals("true")) {
                this.distributedLogging = true;
                String mongoUsername = globalJobParametersMap.get("-mongoUsername");
                String mongoPassword = globalJobParametersMap.get("-mongoPassword");
                String mongoDatabase = globalJobParametersMap.get("-mongoDatabase");
                String mongoAddress = globalJobParametersMap.get("-mongoAddress");
                Integer mongoPort = Integer.valueOf(globalJobParametersMap.get("-mongoPort"));
                String mongoCollectionObservations =
                        globalJobParametersMap.get("-mongoCollectionObservations");

                ServerAddress serverAddress =
                        ServerAddressHelper.createServerAddress(mongoAddress, mongoPort);
                MongoCredential mongoCredentials =
                        MongoCredential.createCredential(
                                mongoUsername, mongoDatabase, mongoPassword.toCharArray());
                MongoClientOptions mongoOptions = MongoClientOptions.builder().build();
                MongoClient mongoClient =
                        new MongoClient(serverAddress, mongoCredentials, mongoOptions);
                MongoDatabase db = mongoClient.getDatabase(mongoDatabase);
                mongoCollection = db.getCollection(mongoCollectionObservations, JSONObject.class);
            } else {
                this.distributedLogging = false;
            }
            this.startTime = System.nanoTime();
            description.put("tupleWidthIn", getTupleSize(input));
            description.put("tupleWidthOut", -1); // not any observation yet
        }
        this.inputCounter++;
        checkIfObservationEnd();
    }

    public <T> void reportOutput(T output) {
        if (this.disableStreamMonitor) {
            return;
        }
        description.put("tupleWidthOut", getTupleSize(output));
        this.outputCounter++;
        checkIfObservationEnd();
    }

    public void reportJoinSelectivity(int size1, int size2, int joinPartners) {
        if (this.disableStreamMonitor) {
            return;
        }
        if (size1 != 0 && size2 != 0) {
            this.joinSelectivities.add((double) joinPartners / (double) (size1 * size2));
        }
    }

    public <T> void reportWindowLength(T state) {
        if (this.disableStreamMonitor) {
            return;
        }
        int length;
        if (state instanceof Long || state instanceof Double || state instanceof Integer) {
            length = 1;
        } else {
            try {
                length = ((ArrayList<Tuple>) state).size();
            } catch (ClassCastException e1) {
                throw new IllegalStateException(e1);
            }
        }
        this.windowLengths.add(length);
    }

    private void checkIfObservationEnd() {
        if (this.disableStreamMonitor) {
            return;
        }
        if (!observationMade) {
            long elapsedTime = System.nanoTime() - this.startTime;
            if (elapsedTime > duration) {
                observationMade = true;
                description.put(
                        "outputRate", ((double) this.outputCounter * 1e9 / (double) elapsedTime));
                description.put(
                        "inputRate", ((double) this.inputCounter * 1e9 / (double) elapsedTime));
                if (this.operator instanceof WrappingFunction) {
                    Double average =
                            this.joinSelectivities.stream()
                                    .mapToDouble(val -> val)
                                    .average()
                                    .orElse(0.0);
                    description.put("realSelectivity", average);
                }
                if (this.operator instanceof StreamFilter) {
                    description.put(
                            "realSelectivity",
                            ((double) this.outputCounter / (double) this.inputCounter));
                } else if (this.operator instanceof WindowOperator) {
                    double averageWindowLength =
                            this.windowLengths.stream()
                                    .mapToDouble(val -> val)
                                    .average()
                                    .orElse(0.0);
                    description.put("realSelectivity", ((double) 1 / averageWindowLength));
                }
                JSONObject json = new JSONObject();
                json.putAll(description);

                // Log data characteristics either locally or in database
                if (this.description.get("id") != null && this.distributedLogging) {
                    mongoCollection.insertOne(json);
                } else if (this.description.get("id") != null && !this.distributedLogging) {
                    logger.info(json.toJSONString());
                }
            }
        }
    }

    private <T> int getTupleSize(T input) {
        try {
            Tuple dt = (Tuple) input;
            return dt.getArity();

        } catch (Exception e) {
            return -1;
        }

        //        if (input instanceof Integer || input instanceof String || input instanceof Double
        // || input instanceof Long) {
        //            return 1;
        //        }
        //
        //        int size = 0;
        //        try {
        //            Values v = (Values) input;
        //            size = v.size();
        //        } catch (ClassCastException e1) {
        //            try {
        //                TupleImpl v = (TupleImpl) input;
        //                size = v.size();
        //            } catch (ClassCastException e2) {
        //                Pair<Object, Object> p = (Pair<Object, Object>) input;
        //                try {
        //                    Values v = (Values) p.getSecond();
        //                    if (p.getSecond() != null) {
        //                        size = v.size();
        //                    }
        //                } catch (ClassCastException e3) {
        //                    if (p.getSecond() instanceof Integer || p.getSecond() instanceof
        // String || p.getSecond()
        //                            instanceof Double || p.getSecond() instanceof Long) {
        //                        size = 1;
        //                    } else {
        //                        ArrayList<Values> l = (ArrayList<Values>) p.getSecond();
        //                        size = l.get(0).size();
        //                    }
        //                }
        //            }
        //        }
        //        return size;
    }
}
