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
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Pair;
import org.radarcns.android.device.AbstractDeviceManager;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.android.util.OfflineProcessor;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.monitor.application.*;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static org.radarcns.android.device.DeviceService.CACHE_RECORDS_SENT_NUMBER;
import static org.radarcns.android.device.DeviceService.CACHE_RECORDS_UNSENT_NUMBER;
import static org.radarcns.android.device.DeviceService.CACHE_TOPIC;
import static org.radarcns.android.device.DeviceService.SERVER_RECORDS_SENT_NUMBER;
import static org.radarcns.android.device.DeviceService.SERVER_RECORDS_SENT_TOPIC;
import static org.radarcns.android.device.DeviceService.SERVER_STATUS_CHANGED;

@SuppressWarnings("WeakerAccess")
public class ApplicationStatusManager
        extends AbstractDeviceManager<ApplicationStatusService, ApplicationState>
        implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationStatusManager.class);
    private static final Long NUMBER_UNKNOWN = -1L;
    private static final int APPLICATION_PROCESSOR_REQUEST_CODE = 72553575;
    private static final int APPLICATION_TZ_PROCESSOR_REQUEST_CODE = 72553576;
    private static final String APPLICATION_PROCESSOR_REQUEST_NAME = ApplicationStatusManager.class.getName();
    private static final String APPLICATION_TZ_PROCESSOR_REQUEST_NAME = APPLICATION_PROCESSOR_REQUEST_NAME + ".timeZone";

    private final AvroTopic<ObservationKey, ApplicationServerStatus> serverTopic;
    private final AvroTopic<ObservationKey, ApplicationRecordCounts> recordCountsTopic;
    private final AvroTopic<ObservationKey, ApplicationUptime> uptimeTopic;
    private final AvroTopic<ObservationKey, ApplicationExternalTime> ntpTopic;
    private final AvroTopic<ObservationKey, ApplicationTimeZone> timeZoneTopic;

    private final OfflineProcessor processor;
    private final long creationTimeStamp;
    private final SntpClient sntpClient;
    private OfflineProcessor tzProcessor;
    private boolean sendIp;

    private String ntpServer;

    private InetAddress previousInetAddress;

    private final BroadcastReceiver serverStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            switch (action) {
                case SERVER_STATUS_CHANGED:
                    final ServerStatusListener.Status status = ServerStatusListener.Status.values()[
                            intent.getIntExtra(SERVER_STATUS_CHANGED, 0)];
                    getState().setServerStatus(status);
                    break;
                case SERVER_RECORDS_SENT_TOPIC:
                    int numberOfRecordsSent = intent.getIntExtra(SERVER_RECORDS_SENT_NUMBER, 0);
                    if (numberOfRecordsSent != -1) {
                        getState().addRecordsSent(numberOfRecordsSent);
                    }
                    break;
                case CACHE_TOPIC:
                    String topic = intent.getStringExtra(CACHE_TOPIC);
                    Pair<Long, Long> numberOfRecords = new Pair<>(
                            intent.getLongExtra(CACHE_RECORDS_UNSENT_NUMBER, NUMBER_UNKNOWN),
                            intent.getLongExtra(CACHE_RECORDS_SENT_NUMBER, NUMBER_UNKNOWN));
                    getState().putCachedRecords(topic, numberOfRecords);
                    break;
            }
        }
    };

    ApplicationStatusManager(ApplicationStatusService service, String ntpServer, long updateRate, long tzUpdateRate, TimeUnit unit, boolean sendIp) {
        super(service);
        this.sendIp = sendIp;
        serverTopic = createTopic("application_server_status", ApplicationServerStatus.class);
        recordCountsTopic = createTopic("application_record_counts", ApplicationRecordCounts.class);
        uptimeTopic = createTopic("application_uptime", ApplicationUptime.class);
        ntpTopic = createTopic("application_external_time", ApplicationExternalTime.class);
        timeZoneTopic = createTopic("application_time_zone", ApplicationTimeZone.class);

        sntpClient = new SntpClient();
        setNtpServer(ntpServer);

        this.processor = new OfflineProcessor.Builder(service, this)
                .requestIdentifier(APPLICATION_PROCESSOR_REQUEST_CODE, APPLICATION_PROCESSOR_REQUEST_NAME)
                .interval(updateRate, unit)
                .wake(false)
                .build();

        setTzUpdateRate(tzUpdateRate, unit);

        setName(getService().getApplicationContext().getApplicationInfo().processName);

        creationTimeStamp = SystemClock.elapsedRealtime();
        previousInetAddress = null;
    }

    @Override
    public void start(@NonNull Set<String> acceptableIds) {
        updateStatus(DeviceStatusListener.Status.READY);

        this.processor.start();
        if (tzProcessor != null) {
            tzProcessor.start();
        }

        logger.info("Starting ApplicationStatusManager");
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVER_STATUS_CHANGED);
        filter.addAction(SERVER_RECORDS_SENT_TOPIC);
        filter.addAction(CACHE_TOPIC);
        LocalBroadcastManager.getInstance(getService())
                .registerReceiver(serverStatusListener, filter);

        updateStatus(DeviceStatusListener.Status.CONNECTED);
    }

    public final synchronized void setNtpServer(String server) {
        if (server == null || server.trim().isEmpty()) {
            this.ntpServer = null;
        } else {
            this.ntpServer = server.trim();
        }
    }

    @Override
    public void run() {
        logger.info("Updating application status");
        try {
            processServerStatus();
            if (processor.isDone()) {
                return;
            }
            processUptime();
            if (processor.isDone()) {
                return;
            }
            processRecordsSent();
            if (processor.isDone()) {
                return;
            }
            processReferenceTime();
        } catch (Exception e) {
            logger.error("Failed to update application status", e);
        }
    }

    public void setApplicationStatusUpdateRate(long period, TimeUnit unit) {
        processor.setInterval(period, unit);
    }

    private void processReferenceTime() {
        String localServer;
        synchronized (this) {
            localServer = ntpServer;
        }
        if (localServer != null) {
            if (sntpClient.requestTime(localServer, 5000)) {
                double delay = sntpClient.getRoundTripTime() / 1000d;
                double time = System.currentTimeMillis() / 1000d;
                double ntpTime =  (sntpClient.getNtpTime() + SystemClock.elapsedRealtime()
                        - sntpClient.getNtpTimeReference()) / 1000d;

                send(ntpTopic, new ApplicationExternalTime(time, ntpTime,
                        localServer, ExternalTimeProtocol.SNTP, delay));
            }
        }
    }

    private void processServerStatus() {
        double time = System.currentTimeMillis() / 1_000d;

        ServerStatus status;
        switch (getState().getServerStatus()) {
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
        String ipAddress = sendIp ? getIpAddress() : null;
        logger.info("Server Status: {}; Device IP: {}", status, ipAddress);

        ApplicationServerStatus value = new ApplicationServerStatus(time, status, ipAddress);

        send(serverTopic, value);
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

    private void processUptime() {
        double time = System.currentTimeMillis() / 1_000d;
        double uptime = (SystemClock.elapsedRealtime() - creationTimeStamp)/1000d;
        send(uptimeTopic, new ApplicationUptime(time, uptime));
    }

    private void processRecordsSent() {
        double time = System.currentTimeMillis() / 1_000d;

        int recordsCachedUnsent = 0;
        int recordsCachedSent = 0;

        for (Pair<Long, Long> records : getState().getCachedRecords().values()) {
            if (!records.first.equals(NUMBER_UNKNOWN)) {
                recordsCachedUnsent += records.first.intValue();
            }
            if (!records.second.equals(NUMBER_UNKNOWN)) {
                recordsCachedSent += records.second.intValue();
            }
        }
        int recordsCached = recordsCachedUnsent + recordsCachedSent;
        int recordsSent = getState().getRecordsSent();

        logger.info("Number of records: {sent: {}, unsent: {}, cached: {}}",
                recordsSent, recordsCachedUnsent, recordsCached);
        send(recordCountsTopic, new ApplicationRecordCounts(time,
                recordsCached, recordsSent, recordsCachedUnsent));
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing ApplicationStatusManager");
        this.processor.close();
        LocalBroadcastManager.getInstance(getService())
                .unregisterReceiver(serverStatusListener);
        super.close();
    }

    public void setSendIp(boolean sendIp) {
        this.sendIp = sendIp;
    }

    public final void setTzUpdateRate(long tzUpdateRate, TimeUnit unit) {
        if (tzUpdateRate > 0) {
            if (this.tzProcessor == null) {
                this.tzProcessor = new OfflineProcessor.Builder(getService(), new TimeZoneUpdater())
                        .requestIdentifier(APPLICATION_TZ_PROCESSOR_REQUEST_CODE, APPLICATION_TZ_PROCESSOR_REQUEST_NAME)
                        .interval(tzUpdateRate, unit)
                        .wake(false)
                        .build();
                if (this.getState().getStatus() == DeviceStatusListener.Status.CONNECTED) {
                    this.tzProcessor.start();
                }
            } else {
                this.tzProcessor.setInterval(tzUpdateRate, unit);
            }
        } else if (this.tzProcessor != null) {
            this.tzProcessor.close();
            this.tzProcessor = null;
        }
    }

    private class TimeZoneUpdater implements Runnable {
        @Override
        public void run() {
            TimeZone tz = TimeZone.getDefault();
            long now = System.currentTimeMillis();
            int offset = tz.getOffset(now) / 1000;
            send(timeZoneTopic, new ApplicationTimeZone(now / 1000d, offset));
        }
    }
}
