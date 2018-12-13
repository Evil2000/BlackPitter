package de.evil2000.blackpitter;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Debug;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchAndDownload extends IntentService {
    private static final String LAST_USED_IP = "lastUsedIp";
    private static final String ALREADY_RUNNING = "alreadyRunning";
    private static final String ALREADY_DOWNLOADED_FILES = "already_downloaded.txt";
    private static final int DOWNLOAD_X_FILES_AFTER_EM = 2;
    private DefaultHttpClient httpClient;
    private SharedPreferences settings;
    private String currentIp;
    private boolean alreadyRunning = false;
    private PowerManager.WakeLock wakeLock;

    private static class FileInfo {
        public String baseUrl;
        public String path;
        public Date timestamp;
        public recmodes recmode;
        public cams cam;

        public enum recmodes {
            PARKING,
            NORMAL,
            EMERG,
            MANUAL
        }

        public enum cams {
            FRONT,
            REAR
        }
    }

    public SearchAndDownload() {
        super("SearchAndDownload");
        //Debug.waitForDebugger();

    }

    private void acquireWakeLock() {
        PowerManager pwrMgr = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pwrMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlackPitter:SearchAndDownload");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        wakeLock.release();
    }

    private void scheduleAlarm(int nextRunInSeconds) {
        H.logI("Trying to register Alarm to fire in " + nextRunInSeconds + " seconds.");
        AlarmManager alrmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(getApplicationContext(), SearchAndDownload.class);
        PendingIntent pi = PendingIntent.getService(getApplicationContext(), 0, i, 0);
        alrmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + nextRunInSeconds * 1000, pi);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        // NOTE: the application context is only available in the onHandleIntent() function, but not in the constructor!
        settings = getSharedPreferences("settings", MODE_PRIVATE);

        acquireWakeLock();

        httpClient = new DefaultHttpClient();

        IPv4 ipv4 = getLocalIpv4Address();
        H.logI("IPv4: " + ipv4.getCIDR());

        if (ipv4 == null) {
            H.logW("Offline. No IPv4 address.");
            beforeStop();
            return;
        }
        if (!ipv4.isPrivate()) {
            beforeStop();
            return;
        }
        ArrayList<String> ips = new ArrayList<String>(ipv4.getAvailableIPs(ipv4.getNumberOfHosts()));
        String lastUsedIp = settings.getString(LAST_USED_IP, "");
        if (!lastUsedIp.isEmpty())
            ips.add(0, lastUsedIp);

        for (String ip : ips) {
            currentIp = ip;
            if (probe("http://" + ip)) {
                try {
                    HttpResponse resp = httpClient.execute(new HttpGet("http://" + ip + "/blackvue_vod.cgi"));
                    H.logI("http://" + ip + "/blackvue_vod.cgi : " + resp.getStatusLine().getStatusCode() + " " + resp.getStatusLine().getReasonPhrase());
                    if (resp.getStatusLine().getStatusCode() == 200 && parseHttpResponse(resp)) {
                        settings.edit().putString(LAST_USED_IP, ip).apply();
                        break;
                    }
                } catch (IOException e) {
                    H.logI("http://" + ip + "/blackvue_vod.cgi : " + e.getMessage());
                }
            }

        }
        beforeStop();
        return;
    }

    private void beforeStop() {
        scheduleAlarm(60);
        releaseWakeLock();
    }

    private boolean parseHttpResponse(HttpResponse resp) {
        String html;
        try {
            html = fetchContent(resp.getEntity().getContent());
        } catch (IOException e) {
            return false;
        }
        ArrayList<FileInfo> files = new ArrayList<FileInfo>();
        Pattern regex = Pattern.compile("^n:([^,]+),s:[0-9]+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher m = regex.matcher(html);
        while (m.find()) {
            files.add(parseFilename(m.group(1)));
        }
        if (files.isEmpty()) {
            H.logW("HTML response doesn't contain any file records.");
            return false;
        }
        files = sortByDate(files, 1);

        ArrayList<String> alreadyDownloadedFiles = readAlreadyDownloadedFilesList();

        /*for (int i = 0; i < alreadyDownloadedFiles.size(); ++i)
            if (!files.contains(alreadyDownloadedFiles.get(i)))
                alreadyDownloadedFiles.remove(i);*/

        for (Iterator<String> i = alreadyDownloadedFiles.iterator(); i.hasNext(); ) {
            String s = i.next();
            boolean contained = false;
            for (FileInfo file : files)
                if ((contained = file.path.equals(s)))
                    break;
            if (!contained)
                i.remove();
        }

        ArrayList<FileInfo> filesToDownload = new ArrayList<FileInfo>();
        for (int i = 0; i < files.size(); i++) {
            for (int x = 0; x <= DOWNLOAD_X_FILES_AFTER_EM; x++) {
                if ((i - x) < 0)
                    break;
                FileInfo prevFileEntry = files.get(i - x);
                FileInfo crntFileEntry = files.get(i);
                if (alreadyDownloadedFiles.contains(crntFileEntry.path) || filesToDownload.contains(crntFileEntry))
                    continue;
                if (prevFileEntry.recmode == FileInfo.recmodes.EMERG || prevFileEntry.recmode == FileInfo.recmodes.MANUAL)
                    filesToDownload.add(crntFileEntry);
            }
        }

        for (FileInfo file : filesToDownload) {
            if (downloadFile(file.baseUrl + file.path))
                alreadyDownloadedFiles.add(file.path);
            else
                H.logW("Error downloading file: " + file.baseUrl + file.path);
            saveAlreadyDownloadedFilesList(alreadyDownloadedFiles);
        }

        return true;
    }

    private ArrayList<String> readAlreadyDownloadedFilesList() {
        StringBuilder buffer = new StringBuilder();
        try (FileInputStream alrdyDwnldld = openFileInput(ALREADY_DOWNLOADED_FILES)) {
            byte[] data = new byte[1];
            while (alrdyDwnldld.read(data) != -1)
                buffer.append(new String(data));
        } catch (IOException ignored) {
        }
        String content = buffer.toString();
        String[] lstCnt = content.split("\n");
        ArrayList<String> ret = new ArrayList<String>(Arrays.asList(lstCnt));
        return ret;
    }

    private void saveAlreadyDownloadedFilesList(ArrayList<String> filesList) {
        try (FileOutputStream alrdyDwnldld = openFileOutput(ALREADY_DOWNLOADED_FILES, MODE_PRIVATE)) {
            for (String file : filesList) {
                alrdyDwnldld.write(file.getBytes());
                alrdyDwnldld.write("\n".getBytes());
            }
            alrdyDwnldld.flush();
        } catch (IOException ignored) {
        }
    }

    private boolean downloadFile(String url) {
        String downloadPath = Environment.getExternalStorageDirectory().toString() + "/download/";
        H.logI("Downloading file: " + url + " into " + downloadPath);
        NotificationManagerCompat notifMgr = NotificationManagerCompat.from(getApplicationContext());

        URL urlUrl = null;
        HttpURLConnection connection;
        long total = 0;
        long todo = -1;

        try {
            urlUrl = new URL(url);
            connection = (HttpURLConnection) urlUrl.openConnection();
        } catch (IOException e) {
            H.logE(e.getMessage());
            return false;
        }

        try (InputStream inStream = connection.getInputStream(); FileOutputStream outStream = new FileOutputStream(downloadPath + "/" + fileBasename(urlUrl.getFile()))) {

            todo = connection.getContentLength();
            NotificationCompat.Builder notification = createNotification(notifMgr, todo);
            notification.setContentText(urlUrl.getHost() + ": " + fileBasename(urlUrl.getFile()));

            byte[] data = new byte[10240];
            int count = 0;
            long percent = 0;
            while ((count = inStream.read(data)) != -1) {
                total += count;
                if (Math.round(total * 100 / todo) > percent) {
                    percent = Math.round(total * 100 / todo);
                    updateNotification(notifMgr, notification, total, todo, "Downloading: " + percent + "%");
                    //H.logI(percent+"%");
                }
                outStream.write(data);
            }

            outStream.flush();

            removeNotification(notifMgr);
        } catch (IOException e) {
            removeNotification(notifMgr);
            e.printStackTrace();
        }
        return (todo == total);
    }

    private NotificationCompat.Builder createNotification(NotificationManagerCompat notificationManager, long max) {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getApplicationContext(), "1");
        mBuilder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("BlackPitter")
                .setContentText("Downloading")
                .setPriority(NotificationCompat.PRIORITY_LOW);
        mBuilder.setProgress((int) max, 0, false);
        notificationManager.notify(1, mBuilder.build());
        return mBuilder;
    }

    private void updateNotification(NotificationManagerCompat notificationManager, NotificationCompat.Builder notification, long currentProgress, long maxProgress, String message) {
        notification.setContentTitle(message);
        notification.setProgress((int) maxProgress, (int) currentProgress, false);
        notificationManager.notify(1, notification.build());
    }

    private void removeNotification(NotificationManagerCompat notificationManager) {
        notificationManager.cancel(1);
    }

    /**
     * Sort ArrayList<FileInfo> by date-field "timestamp" and front/rear cam.
     *
     * @param inArray   Array to sort
     * @param sortOrder 0 - sort normal, 1 - sort reverse
     * @return sorted array
     */
    private ArrayList<FileInfo> sortByDate(ArrayList<FileInfo> inArray, final int sortOrder) {
        Collections.sort(inArray, new Comparator<FileInfo>() {
            @Override
            public int compare(FileInfo fileInfo2, FileInfo fileInfo1) {
                int cmprd;
                if (sortOrder > 0)
                    cmprd = fileInfo1.timestamp.compareTo(fileInfo2.timestamp);
                else
                    cmprd = fileInfo2.timestamp.compareTo(fileInfo1.timestamp);

                if (cmprd == 0)
                    cmprd = fileInfo2.cam.compareTo(fileInfo1.cam);

                return cmprd;
            }
        });
        return inArray;
    }

    private FileInfo parseFilename(String inFile) {
        String basename = fileBasename(inFile);
        FileInfo fileInfo = new FileInfo();
        fileInfo.baseUrl = "http://" + currentIp;
        fileInfo.path = inFile;

        DateFormat df = new SimpleDateFormat("yyyyMMdd_HHmmss");
        try {
            fileInfo.timestamp = df.parse(basename);
        } catch (ParseException e) {
            fileInfo.timestamp = null;
        }

        char recmode = basename.charAt(16);
        if (recmode == 'P') {
            fileInfo.recmode = FileInfo.recmodes.PARKING;
        } else if (recmode == 'N') {
            fileInfo.recmode = FileInfo.recmodes.NORMAL;
        } else if (recmode == 'E') {
            fileInfo.recmode = FileInfo.recmodes.EMERG;
        } else if (recmode == 'M') {
            fileInfo.recmode = FileInfo.recmodes.MANUAL;
        }

        char cam = basename.charAt(17);
        if (cam == 'F') {
            fileInfo.cam = FileInfo.cams.FRONT;
        } else if (cam == 'R') {
            fileInfo.cam = FileInfo.cams.REAR;
        }

        return fileInfo;
    }

    private String fileBasename(String filepath) {
        return (new File(filepath)).getName();
    }

    private String fetchContent(InputStream in) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line + "\n");
            }
        } catch (IOException e) {
            H.logE(e.getMessage());
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                H.logE(e.getMessage());
            }
        }
        return sb.toString();
    }

    private boolean probe(String uri) {
        try {
            URL url = new URL(uri);
            String hostAddress;
            hostAddress = InetAddress.getByName(url.getHost()).getHostAddress();
            int port = url.getPort();
            if (port < 0) {
                port = url.getProtocol().equals("https") ? 443 : 80;
            }
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(hostAddress, port), 100);
            socket.close();
        } catch (Exception ex) {
            H.logE(ex.toString());
            return false;
        }
        return true;
    }

    public static IPv4 getLocalIpv4Address() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                Iterator<InterfaceAddress> intfAddresses = intf.getInterfaceAddresses().iterator();
                while (intfAddresses.hasNext()) {
                    InterfaceAddress intfAddress = intfAddresses.next();
                    InetAddress inetAddress = intfAddress.getAddress();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        IPv4 ipv4 = new IPv4(inetAddress.getHostAddress() + "/" + intfAddress.getNetworkPrefixLength());
                        if (ipv4.isPrivate())
                            return ipv4;
                    }/* else if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet6Address) {
                        return inetAddress.getHostAddress() + "/" + intfAddress.getNetworkPrefixLength();
                    }*/
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return null;
    }
}

