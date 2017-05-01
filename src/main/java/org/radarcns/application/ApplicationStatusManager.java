/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.application;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.annotation.NonNull;
import android.util.Pair;

import org.radarcns.android.data.DataCache;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.DeviceManager;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.key.MeasurementKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.radarcns.android.device.DeviceService.CACHE_TOPIC;
import static org.radarcns.android.device.DeviceService.CACHE_RECORDS_SENT_NUMBER;
import static org.radarcns.android.device.DeviceService.CACHE_RECORDS_UNSENT_NUMBER;
import static org.radarcns.android.device.DeviceService.SERVER_RECORDS_SENT_NUMBER;
import static org.radarcns.android.device.DeviceService.SERVER_RECORDS_SENT_TOPIC;
import static org.radarcns.android.device.DeviceService.SERVER_STATUS_CHANGED;

public class ApplicationStatusManager implements DeviceManager {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationStatusManager.class);
    private static final long APPLICATION_UPDATE_INTERVAL_DEFAULT = 20; // seconds
    private static final Long NUMBER_UNKNOWN = -1L;

    private final TableDataHandler dataHandler;
    private final Context context;

    private final ApplicationStatusService applicationStatusService;

    private final DataCache<MeasurementKey, ApplicationServerStatus> serverStatusTable;
    private final DataCache<MeasurementKey, ApplicationUptime> uptimeTable;
    private final DataCache<MeasurementKey, ApplicationRecordCounts> recordCountsTable;

    private final ApplicationState deviceStatus;

    private String deviceName;
    private ScheduledFuture<?> serverStatusUpdateFuture;
    private final ScheduledExecutorService executor;

    private final long creationTimeStamp;
    private boolean isRegistered = false;
    private InetAddress previousInetAddress;

    private final BroadcastReceiver serverStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(SERVER_STATUS_CHANGED)) {
                final ServerStatusListener.Status status = ServerStatusListener.Status.values()[intent.getIntExtra(SERVER_STATUS_CHANGED, 0)];
                deviceStatus.setServerStatus(status);
            } else if (intent.getAction().equals(SERVER_RECORDS_SENT_TOPIC)) {
                int numberOfRecordsSent = intent.getIntExtra(SERVER_RECORDS_SENT_NUMBER, 0);
                if (numberOfRecordsSent != -1) {
                    deviceStatus.addRecordsSent(numberOfRecordsSent);
                }
            } else if (intent.getAction().equals(CACHE_TOPIC)) {
                String topic = intent.getStringExtra(CACHE_TOPIC);
                Pair<Long, Long> numberOfRecords = new Pair<>(
                        intent.getLongExtra(CACHE_RECORDS_UNSENT_NUMBER, NUMBER_UNKNOWN),
                        intent.getLongExtra(CACHE_RECORDS_SENT_NUMBER, NUMBER_UNKNOWN));
                deviceStatus.putCachedRecords(topic, numberOfRecords);
            }
        }
    };

    public ApplicationStatusManager(Context context,
            ApplicationStatusService applicationStatusService, String groupId, String sourceId,
            TableDataHandler dataHandler, ApplicationStatusTopics topics, String devicesToConnect) {
        this.dataHandler = dataHandler;
        this.serverStatusTable = dataHandler.getCache(topics.getServerTopic());
        this.uptimeTable = dataHandler.getCache(topics.getUptimeTopic());
        this.recordCountsTable = dataHandler.getCache(topics.getRecordCountsTopic());

        this.applicationStatusService = applicationStatusService;

        this.context = context;
        this.deviceStatus = new ApplicationState();
        this.deviceStatus.getId().setUserId(groupId);
        this.deviceStatus.getId().setSourceId(sourceId);

        deviceName = context.getString(R.string.app_name);
        creationTimeStamp = System.currentTimeMillis();

        // Scheduler TODO: run executor with existing thread pool/factory
        executor = Executors.newSingleThreadScheduledExecutor();
        previousInetAddress = null;
    }

    @Override
    public void start(@NonNull Set<String> acceptableIds) {
        logger.info("Starting ApplicationStatusManager");
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVER_STATUS_CHANGED);
        filter.addAction(SERVER_RECORDS_SENT_TOPIC);
        filter.addAction(CACHE_TOPIC);
        context.registerReceiver(serverStatusListener, filter);

        // Application status
        setApplicationStatusUpdateRate(APPLICATION_UPDATE_INTERVAL_DEFAULT);

        isRegistered = true;
        updateStatus(DeviceStatusListener.Status.CONNECTED);
    }

    public final synchronized void setApplicationStatusUpdateRate(final long period) {
        if (serverStatusUpdateFuture != null) {
            serverStatusUpdateFuture.cancel(false);
        }

        serverStatusUpdateFuture = executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                logger.info("Updating application status");
                try {
                    processServerStatus();
                    processUptime();
                    processRecordsSent();
                } catch (Exception e) {
                    logger.error("Failed to update application status", e);
                }
            }
        }, 0, period, TimeUnit.SECONDS);

        logger.info("App status updater: listener activated and set to a period of {}", period);
    }

    @Override
    public boolean isClosed() {
        return !isRegistered;
    }

    @Override
    public BaseDeviceState getState() {
        return deviceStatus;
    }

    @Override
    public String getName() {
        return deviceName;
    }

    public void processServerStatus() {
        double timeReceived = System.currentTimeMillis() / 1_000d;

        ServerStatus status;
        switch (deviceStatus.getServerStatus()) {
            case CONNECTED:
            case READY:
            case UPLOADING:
                status = ServerStatus.CONNECTED;
                break;
            case DISCONNECTED:
            case DISABLED:
            case UPLOADING_FAILED:
                status = ServerStatus.DISCONNECTED;
                break;
            default:
                status = ServerStatus.UNKNOWN;
        }
        String ipAddress = getIpAddress();
        logger.info("Server Status: {}; Device IP: {}", status, ipAddress);

        ApplicationServerStatus value = new ApplicationServerStatus(timeReceived, timeReceived, status, ipAddress);

        dataHandler.addMeasurement(serverStatusTable, deviceStatus.getId(), value);
    }

    private String getIpAddress() {
        // Find Ip via NetworkInterfaces. Works via wifi, ethernet and mobile network
        try {
            if (previousInetAddress == null ||
                    NetworkInterface.getByInetAddress(previousInetAddress) == null) {
                for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                    NetworkInterface intf = en.nextElement();
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                            // This finds both xx.xx.xx ip and rmnet. Last one is always ip.
                            previousInetAddress = inetAddress;
                        }
                    }
                }
            }
        } catch (SocketException ex) {
            logger.warn("No IP Address could be determined", ex);
            previousInetAddress = null;
        }
        if (previousInetAddress == null) {
            return null;
        } else {
            return previousInetAddress.getHostAddress();
        }
    }

    public void processUptime() {
        double timeReceived = System.currentTimeMillis() / 1_000d;

        double uptime = (System.currentTimeMillis() - creationTimeStamp)/1000d;
        ApplicationUptime value = new ApplicationUptime(timeReceived, timeReceived, uptime);

        dataHandler.addMeasurement(uptimeTable, deviceStatus.getId(), value);
    }

    public void processRecordsSent() {
        double timeReceived = System.currentTimeMillis() / 1_000d;

        int recordsCachedUnsent = 0;
        int recordsCachedSent = 0;

        for (Pair<Long, Long> records : deviceStatus.getCachedRecords().values()) {
            if (!records.first.equals(NUMBER_UNKNOWN)) {
                recordsCachedUnsent += records.first.intValue();
            }
            if (!records.second.equals(NUMBER_UNKNOWN)) {
                recordsCachedSent += records.second.intValue();
            }
        }
        int recordsCached = recordsCachedUnsent + recordsCachedSent;
        int recordsSent = deviceStatus.getRecordsSent();

        logger.info("Number of records: {sent: {}, unsent: {}, cached: {}}",
                recordsSent, recordsCachedUnsent, recordsCached);
        ApplicationRecordCounts value = new ApplicationRecordCounts(timeReceived, timeReceived,
                recordsCached, recordsSent, recordsCachedUnsent);
        dataHandler.addMeasurement(recordCountsTable, deviceStatus.getId(), value);
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing ApplicationStatusManager");
        executor.shutdown();
        context.unregisterReceiver(serverStatusListener);
        isRegistered = false;
        updateStatus(DeviceStatusListener.Status.DISCONNECTED);
    }

    private synchronized void updateStatus(DeviceStatusListener.Status status) {
        this.deviceStatus.setStatus(status);
        this.applicationStatusService.deviceStatusUpdated(this, status);
    }
}
