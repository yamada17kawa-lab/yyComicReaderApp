package com.comic.reader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LanTransferManager {
    public static final int DISCOVERY_UDP_PORT = 39527;
    public static final int DEFAULT_HTTP_PORT = 39528;

    public static class PeerInfo {
        public String app = "YYComicReader";
        public String deviceId = "";
        public String deviceName = "";
        public String platform = "";
        public String ip = "";
        public int port = DEFAULT_HTTP_PORT;
        public int comicCount = 0;
        public boolean requirePassword = false;
        public int versionCode = 0;
        public String versionName = "";
        public String sessionToken = "";
    }

    public static class WhitelistEntry {
        public String deviceId = "";
        public String deviceName = "";
        public String platform = "";
        public long addedTime = 0;
    }

    public static class BlacklistEntry {
        public String deviceId = "";
        public String deviceName = "";
        public String platform = "";
        public String ip = "";
        public long addedTime = 0;
    }

    public static class PairRequestState {
        public String reqId = "";
        public String fromId = "";
        public String fromName = "";
        public String fromPlatform = "";
        public String fromIp = "";
        public int fromPort = DEFAULT_HTTP_PORT;
        public String status = "pending"; // "pending", "accepted", "rejected", "timeout"
        public long createdTime = 0;
        public String sessionToken = "";

        public boolean isExpired() {
            return (System.currentTimeMillis() - createdTime) > 30000;
        }
    }

    public static class ConnectResult {
        public boolean ok = false;
        public String status = ""; // "accepted", "need_password", "wrong_password", "need_confirm", "rejected", "timeout", "error", "cancelled"
        public String message = "";
        public String sessionToken = "";
        public PeerInfo peer = null;
    }

    public interface OnPairRequestListener {
        void onPairRequestReceived(PairRequestState request);
    }

    public static class RemoteComic {
        public String id = "";
        public String title = "";
        public int pageCount = 0;
        public int readCount = 0;
        public long lastReadTime = 0;
        public int lastReadPage = 1;
        public long importTime = 0;
    }

    public static class TransferResult {
        public int transferredCount = 0;
        public int skippedCount = 0;
        public List<String> skippedTitles = new ArrayList<>();
    }

    public static String normalizeTitleKey(String title) {
        if (title == null) return "";
        return sanitizeName(title).trim().toLowerCase();
    }

    public boolean hasLocalActiveComic(String title) {
        String key = normalizeTitleKey(title);
        if (key.isEmpty()) return false;
        List<ComicItem> active = comicListProvider != null ? comicListProvider.getActiveComics() : null;
        if (active != null) {
            for (ComicItem c : active) {
                if (c != null && !c.isMissing() && normalizeTitleKey(c.getTitle()).equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    public interface ComicListProvider {
        List<ComicItem> getActiveComics();
    }

    public interface OnComicReceivedListener {
        void onComicReceived(String comicTitle, File comicFolder);
    }

    public interface OnTransferProgressListener {
        void onProgress(String status, int percent, String detail);
    }

    public interface OnPeerConnectedListener {
        void onPeerConnected(PeerInfo peer);
    }

    public interface OnPeerDisconnectedListener {
        void onPeerDisconnected(String fromId, String fromName, String fromIp);
    }

    public interface OnBatchTransferCompleteListener {
        void onBatchComplete(String mode, String peerName, int transferredCount, int skippedCount);
    }

    public interface OnRemoteOperationListener {
        boolean onRenameComic(String id, String title, String newTitle);
        boolean onSetComicCoverPage(String id, int pageIndex);
        boolean onResetComicCover(String id);
        boolean onSetComicCoverFile(String id, byte[] imageBytes, String ext);
        boolean onDeleteComic(String id, String title);
        void onRecordRead(String id, String title, int page, boolean inc);
    }

    private final Context context;
    private final ComicListProvider comicListProvider;
    private final OnComicReceivedListener onComicReceivedListener;
    private final OnTransferProgressListener onTransferProgressListener;
    private final OnPeerConnectedListener onPeerConnectedListener;
    private OnPeerDisconnectedListener onPeerDisconnectedListener;
    public void setOnPeerDisconnectedListener(OnPeerDisconnectedListener listener) {
        this.onPeerDisconnectedListener = listener;
    }
    private final OnBatchTransferCompleteListener onBatchTransferCompleteListener;
    private OnRemoteOperationListener onRemoteOperationListener;

    public void setOnRemoteOperationListener(OnRemoteOperationListener listener) {
        this.onRemoteOperationListener = listener;
    }

    public interface OnUpdateReceivedListener {
        void onUpdateReceived(ApkUpdateManager.UpdateInfo info);
    }
    private OnUpdateReceivedListener onUpdateReceivedListener;
    public void setOnUpdateReceivedListener(OnUpdateReceivedListener listener) {
        this.onUpdateReceivedListener = listener;
    }
    private OnPairRequestListener onPairRequestListener;
    private final Map<String, PairRequestState> pairRequests = new ConcurrentHashMap<>();

    public void setOnPairRequestListener(OnPairRequestListener listener) {
        this.onPairRequestListener = listener;
    }

    private volatile String currentSessionToken = null;
    private volatile String currentConnectedPeerId = null;

    public String getCurrentSessionToken() {
        return currentSessionToken;
    }

    public boolean isRequestAuthorized(String fromId, String remoteIp, String token) {
        if (fromId != null && !fromId.isEmpty() && isDeviceWhitelisted(fromId)) {
            return true;
        }
        if (currentSessionToken != null && !currentSessionToken.isEmpty()
                && token != null && currentSessionToken.equals(token)) {
            return true;
        }
        if (token != null && !token.isEmpty()) {
            for (PairRequestState pr : pairRequests.values()) {
                if (!pr.isExpired() && token.equals(pr.sessionToken)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getDeviceId() {
        android.content.SharedPreferences sp = context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE);
        String id = sp.getString("device_id", null);
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            sp.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public boolean isRequirePassword() {
        return context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .getBoolean("require_password", false);
    }

    public void setRequirePassword(boolean require) {
        context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("require_password", require).apply();
    }

    public String getConnectionPassword() {
        return context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .getString("connection_password", "");
    }

    public void setConnectionPassword(String password) {
        context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .edit().putString("connection_password", password != null ? password : "").apply();
    }

    public boolean hasConnectionPassword() {
        String pwd = getConnectionPassword();
        return pwd != null && !pwd.trim().isEmpty();
    }

    public boolean isSelfIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) return false;
        String clean = ip.trim();
        if (clean.toLowerCase().startsWith("http://")) clean = clean.substring(7);
        if (clean.toLowerCase().startsWith("https://")) clean = clean.substring(8);
        if (clean.contains(":")) clean = clean.split(":")[0];
        if (clean.contains("/")) clean = clean.replace("/", "");
        if ("127.0.0.1".equals(clean) || "localhost".equalsIgnoreCase(clean) || "0.0.0.0".equals(clean) || "::1".equals(clean)) {
            return true;
        }
        List<String> localIps = getLocalLanIps();
        for (String myIp : localIps) {
            if (myIp != null && myIp.trim().equalsIgnoreCase(clean)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSelf(PeerInfo p) {
        if (p == null) return false;
        String myId = getDeviceId();
        if (myId != null && !myId.isEmpty() && myId.equalsIgnoreCase(p.deviceId)) {
            return true;
        }
        if (isSelfIp(p.ip)) {
            return true;
        }
        return false;
    }

    public static boolean isValidPassword(String password) {
        if (password == null || password.length() < 8) return false;
        boolean hasDigit = false;
        boolean hasLetter = false;
        boolean hasSpecial = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isDigit(c)) hasDigit = true;
            else if (Character.isLetter(c)) hasLetter = true;
            else hasSpecial = true;
        }
        return hasDigit && hasLetter && hasSpecial;
    }

    public List<WhitelistEntry> getWhitelist() {
        List<WhitelistEntry> list = new ArrayList<>();
        String json = context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .getString("whitelist_json", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                WhitelistEntry w = new WhitelistEntry();
                w.deviceId = obj.optString("deviceId", "");
                w.deviceName = obj.optString("deviceName", "");
                w.platform = obj.optString("platform", "");
                w.addedTime = obj.optLong("addedTime", 0);
                if (!w.deviceId.isEmpty()) list.add(w);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public void saveWhitelist(List<WhitelistEntry> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (WhitelistEntry w : list) {
                if (w == null || w.deviceId.isEmpty()) continue;
                JSONObject obj = new JSONObject();
                try {
                    obj.put("deviceId", w.deviceId);
                    obj.put("deviceName", w.deviceName != null ? w.deviceName : "");
                    obj.put("platform", w.platform != null ? w.platform : "");
                    obj.put("addedTime", w.addedTime);
                    arr.put(obj);
                } catch (Exception ignored) {}
            }
        }
        context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .edit().putString("whitelist_json", arr.toString()).apply();
    }

    public boolean isDeviceWhitelisted(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return false;
        List<WhitelistEntry> list = getWhitelist();
        for (WhitelistEntry w : list) {
            if (deviceId.equalsIgnoreCase(w.deviceId)) return true;
        }
        return false;
    }

    public void addToWhitelist(String deviceId, String deviceName, String platform) {
        if (deviceId == null || deviceId.isEmpty()) return;
        List<WhitelistEntry> list = getWhitelist();
        for (WhitelistEntry w : list) {
            if (deviceId.equalsIgnoreCase(w.deviceId)) {
                w.deviceName = deviceName;
                w.platform = platform;
                w.addedTime = System.currentTimeMillis();
                saveWhitelist(list);
                return;
            }
        }
        WhitelistEntry entry = new WhitelistEntry();
        entry.deviceId = deviceId;
        entry.deviceName = deviceName != null ? deviceName : "未知设备";
        entry.platform = platform != null ? platform : "";
        entry.addedTime = System.currentTimeMillis();
        list.add(entry);
        saveWhitelist(list);
    }

    public void removeFromWhitelist(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return;
        List<WhitelistEntry> list = getWhitelist();
        list.removeIf(w -> deviceId.equalsIgnoreCase(w.deviceId));
        saveWhitelist(list);
    }

    public synchronized List<BlacklistEntry> getBlacklist() {
        List<BlacklistEntry> list = new ArrayList<>();
        String json = context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .getString("blacklist_json", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                BlacklistEntry b = new BlacklistEntry();
                b.deviceId = obj.optString("deviceId", "");
                b.deviceName = obj.optString("deviceName", "");
                b.platform = obj.optString("platform", "");
                b.ip = obj.optString("ip", "");
                b.addedTime = obj.optLong("addedTime", 0);
                if (!b.deviceId.isEmpty() || !b.ip.isEmpty()) list.add(b);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public synchronized void saveBlacklist(List<BlacklistEntry> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (BlacklistEntry b : list) {
                if (b == null) continue;
                JSONObject obj = new JSONObject();
                try {
                    obj.put("deviceId", b.deviceId != null ? b.deviceId : "");
                    obj.put("deviceName", b.deviceName != null ? b.deviceName : "");
                    obj.put("platform", b.platform != null ? b.platform : "");
                    obj.put("ip", b.ip != null ? b.ip : "");
                    obj.put("addedTime", b.addedTime);
                    arr.put(obj);
                } catch (Exception ignored) {}
            }
        }
        context.getSharedPreferences("lan_settings", Context.MODE_PRIVATE)
                .edit().putString("blacklist_json", arr.toString()).apply();
    }

    public synchronized boolean isDeviceBlacklisted(String deviceId, String ip) {
        List<BlacklistEntry> list = getBlacklist();
        for (BlacklistEntry b : list) {
            if (deviceId != null && !deviceId.isEmpty() && deviceId.equalsIgnoreCase(b.deviceId)) return true;
            if (ip != null && !ip.isEmpty() && ip.equalsIgnoreCase(b.ip)) return true;
        }
        return false;
    }

    public synchronized void addToBlacklist(String devId, String devName, String ip, String platform) {
        List<BlacklistEntry> list = getBlacklist();
        for (BlacklistEntry b : list) {
            if ((devId != null && !devId.isEmpty() && devId.equalsIgnoreCase(b.deviceId)) ||
                (ip != null && !ip.isEmpty() && ip.equalsIgnoreCase(b.ip))) {
                return;
            }
        }
        BlacklistEntry b = new BlacklistEntry();
        b.deviceId = devId != null ? devId : "";
        b.deviceName = devName != null ? devName : "未知设备";
        b.ip = ip != null ? ip : "";
        b.platform = platform != null ? platform : "";
        b.addedTime = System.currentTimeMillis();
        list.add(b);
        saveBlacklist(list);
        if (devId != null && !devId.isEmpty()) {
            removeFromWhitelist(devId);
        }
    }

    public synchronized void removeFromBlacklist(String devId, String ip) {
        List<BlacklistEntry> list = getBlacklist();
        list.removeIf(b -> (devId != null && !devId.isEmpty() && devId.equalsIgnoreCase(b.deviceId)) ||
                           (ip != null && !ip.isEmpty() && ip.equalsIgnoreCase(b.ip)));
        saveBlacklist(list);
    }

    public void disconnect(PeerInfo peer) {
        currentSessionToken = null;
        currentConnectedPeerId = null;
        if (peer == null || peer.ip == null || peer.ip.isEmpty()) return;
        int port = peer.port > 0 ? peer.port : DEFAULT_HTTP_PORT;
        serverPool.execute(() -> {
            try {
                String url = "http://" + peer.ip + ":" + port + "/api/disconnect?fromId="
                        + URLEncoder.encode(getDeviceId(), "UTF-8")
                        + "&fromName=" + URLEncoder.encode(getDeviceName(), "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.getResponseCode();
            } catch (Exception ignored) {}
        });
    }

    public boolean respondPairRequest(String reqId, boolean accept) {
        if (reqId == null || reqId.isEmpty()) return false;
        PairRequestState state = pairRequests.get(reqId);
        if (state != null) {
            if (state.isExpired()) {
                state.status = "timeout";
                return false;
            }
            state.status = accept ? "accepted" : "rejected";
            if (accept) {
                currentSessionToken = (state.sessionToken != null && !state.sessionToken.isEmpty()) ? state.sessionToken : java.util.UUID.randomUUID().toString().replace("-", "");
                currentConnectedPeerId = state.fromId;
                state.sessionToken = currentSessionToken;
                PeerInfo peer = new PeerInfo();
                peer.app = "YYComicReader";
                peer.deviceId = state.fromId;
                peer.deviceName = state.fromName;
                peer.platform = state.fromPlatform;
                peer.ip = state.fromIp;
                peer.port = state.fromPort;
                peer.sessionToken = currentSessionToken;
                if (onPeerConnectedListener != null) {
                    onPeerConnectedListener.onPeerConnected(peer);
                }
            }
            return true;
        }
        return false;
    }

    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private volatile boolean running = false;
    private int listeningPort = DEFAULT_HTTP_PORT;
    private final ExecutorService serverPool = Executors.newCachedThreadPool();

    public LanTransferManager(
            Context context,
            ComicListProvider comicListProvider,
            OnComicReceivedListener onComicReceivedListener,
            OnTransferProgressListener onTransferProgressListener) {
        this(context, comicListProvider, onComicReceivedListener, onTransferProgressListener, null, null);
    }

    public LanTransferManager(
            Context context,
            ComicListProvider comicListProvider,
            OnComicReceivedListener onComicReceivedListener,
            OnTransferProgressListener onTransferProgressListener,
            OnPeerConnectedListener onPeerConnectedListener,
            OnBatchTransferCompleteListener onBatchTransferCompleteListener) {
        this.context = context.getApplicationContext();
        this.comicListProvider = comicListProvider;
        this.onComicReceivedListener = onComicReceivedListener;
        this.onTransferProgressListener = onTransferProgressListener;
        this.onPeerConnectedListener = onPeerConnectedListener;
        this.onBatchTransferCompleteListener = onBatchTransferCompleteListener;
    }

    public int getListeningPort() {
        return listeningPort;
    }

    public String getDeviceName() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        String model = Build.MODEL != null ? Build.MODEL : "Android";
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        }
        return capitalize(manufacturer) + " " + model;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) return s;
        return Character.toUpperCase(first) + s.substring(1);
    }

    public void start() {
        if (running) return;
        running = true;

        // 1. 启动 HTTP TCP 服务端
        for (int port = DEFAULT_HTTP_PORT; port <= DEFAULT_HTTP_PORT + 7; port++) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
                listeningPort = port;
                serverPool.execute(this::acceptHttpLoop);
                break;
            } catch (Exception e) {
                serverSocket = null;
            }
        }

        // 2. 启动 UDP 广播响应服务
        serverPool.execute(this::listenUdpDiscoveryLoop);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        try {
            if (udpSocket != null) udpSocket.close();
        } catch (Exception ignored) {}
    }

    public List<String> getLocalLanIps() {
        List<String> wifiIps = new ArrayList<>();
        List<String> otherIps = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface ni : Collections.list(interfaces)) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String name = ni.getName() != null ? ni.getName().toLowerCase() : "";
                    if (name.contains("dummy") || name.contains("rmnet") || name.contains("ccmni") || name.contains("tun") || name.contains("ppp")) {
                        continue;
                    }
                    boolean isWifiOrAp = name.contains("wlan") || name.contains("ap") || name.contains("eth") || name.contains("swlan");
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            String ip = addr.getHostAddress();
                            if (ip != null && !ip.startsWith("169.254.")) {
                                if (isWifiOrAp) wifiIps.add(ip);
                                else otherIps.add(ip);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        List<String> all = new ArrayList<>(wifiIps);
        for (String ip : otherIps) {
            if (!all.contains(ip)) all.add(ip);
        }
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String wifiIp = String.format(java.util.Locale.US, "%d.%d.%d.%d",
                            (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                    if (!all.contains(wifiIp)) all.add(wifiIp);
                }
            }
        } catch (Exception ignored) {}
        return all;
    }

    public JSONObject buildSelfPeerJson(String preferredIp) {
        JSONObject obj = new JSONObject();
        try {
            List<String> ips = getLocalLanIps();
            String ip = (preferredIp != null && !preferredIp.isEmpty())
                    ? preferredIp
                    : (!ips.isEmpty() ? ips.get(0) : "127.0.0.1");
            int count = 0;
            try {
                List<ComicItem> active = comicListProvider.getActiveComics();
                if (active != null) count = active.size();
            } catch (Exception ignored) {}

            obj.put("app", "YYComicReader");
            obj.put("deviceId", getDeviceId());
            obj.put("deviceName", getDeviceName());
            obj.put("platform", "Android");
            obj.put("ip", ip);
            obj.put("port", listeningPort);
            obj.put("comicCount", count);
            obj.put("requirePassword", isRequirePassword());
            int vCode = ApkUpdateManager.getLocalVersionCode(context);
            String vName = ApkUpdateManager.getLocalVersionName(context);
            obj.put("versionCode", vCode);
            obj.put("versionName", vName);
        } catch (Exception ignored) {}
        return obj;
    }

    private String matchBestLocalIpForRemote(InetAddress remoteAddr) {
        List<String> ips = getLocalLanIps();
        if (remoteAddr != null && remoteAddr.getHostAddress() != null) {
            String remoteStr = remoteAddr.getHostAddress();
            int dot = remoteStr.lastIndexOf('.');
            if (dot > 0) {
                String prefix = remoteStr.substring(0, dot + 1);
                for (String ip : ips) {
                    if (ip.startsWith(prefix)) return ip;
                }
            }
        }
        return !ips.isEmpty() ? ips.get(0) : "127.0.0.1";
    }

    private void listenUdpDiscoveryLoop() {
        try {
            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.setBroadcast(true);
            udpSocket.bind(new InetSocketAddress(DISCOVERY_UDP_PORT));

            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);
                String senderIp = packet.getAddress() != null ? packet.getAddress().getHostAddress() : "";
                if (isSelfIp(senderIp)) {
                    continue; // 严防本机回环自连接
                }
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith("YYCOMIC_DISCOVER")) {
                    String bestIp = matchBestLocalIpForRemote(packet.getAddress());
                    String reply = "YYCOMIC_HERE|" + buildSelfPeerJson(bestIp).toString();
                    byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket outPacket = new DatagramPacket(
                            replyBytes,
                            replyBytes.length,
                            packet.getAddress(),
                            packet.getPort()
                    );
                    udpSocket.send(outPacket);
                }
            }
        } catch (Exception ignored) {}
    }

    public List<PeerInfo> discoverPeers() {
        Map<String, PeerInfo> found = new ConcurrentHashMap<>();
        List<String> localIps = getLocalLanIps();
        Set<String> selfIps = new HashSet<>(localIps);
        selfIps.add("127.0.0.1");

        // 1. UDP 广播发现
        Thread udpThread = new Thread(() -> {
            try (DatagramSocket client = new DatagramSocket()) {
                client.setBroadcast(true);
                client.setSoTimeout(300);
                byte[] req = "YYCOMIC_DISCOVER".getBytes(StandardCharsets.UTF_8);

                try {
                    client.send(new DatagramPacket(req, req.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_UDP_PORT));
                } catch (Exception ignored) {}

                for (String myIp : localIps) {
                    int dot = myIp.lastIndexOf('.');
                    if (dot > 0) {
                        String bcast = myIp.substring(0, dot + 1) + "255";
                        try {
                            client.send(new DatagramPacket(req, req.length, InetAddress.getByName(bcast), DISCOVERY_UDP_PORT));
                        } catch (Exception ignored) {}
                    }
                }

                long deadline = System.currentTimeMillis() + 900;
                byte[] recvBuf = new byte[4096];
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket resp = new DatagramPacket(recvBuf, recvBuf.length);
                        client.receive(resp);
                        String text = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                        if (text.startsWith("YYCOMIC_HERE|")) {
                            String json = text.substring("YYCOMIC_HERE|".length());
                            PeerInfo p = parsePeerInfo(new JSONObject(json));
                            String remoteIp = resp.getAddress().getHostAddress();
                            if (p != null && "YYComicReader".equals(p.app) && remoteIp != null) {
                                p.ip = remoteIp;
                                if (!isSelf(p) && !isSelfIp(remoteIp)) {
                                    String peerKey = (p.deviceId != null && !p.deviceId.isEmpty()) ? p.deviceId : (p.ip + ":" + p.port);
                                    found.put(peerKey, p);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
        udpThread.start();

        // 2. 同时对当前 Wi-Fi 网段并发 HTTP 探测 (兼容路由器隔离 UDP 广播或电脑防火墙拦截 UDP)
        List<String> candidateIps = new ArrayList<>();
        for (int i = 0; i < Math.min(2, localIps.size()); i++) {
            String myIp = localIps.get(i);
            int dot = myIp.lastIndexOf('.');
            if (dot > 0) {
                String prefix = myIp.substring(0, dot + 1);
                for (int host = 1; host <= 254; host++) {
                    String target = prefix + host;
                    if (!selfIps.contains(target)) {
                        candidateIps.add(target);
                    }
                }
            }
        }

        if (!candidateIps.isEmpty()) {
            ExecutorService probePool = Executors.newFixedThreadPool(36);
            CountDownLatch latch = new CountDownLatch(candidateIps.size());
            for (String ip : candidateIps) {
                probePool.execute(() -> {
                    try {
                        String key = ip + ":" + DEFAULT_HTTP_PORT;
                        if (!found.containsKey(key)) {
                            PeerInfo p = pingPeer(ip, DEFAULT_HTTP_PORT, 550);
                            if (p != null && "YYComicReader".equals(p.app) && !isSelf(p) && !isSelfIp(ip)) {
                                String peerKey = (p.deviceId != null && !p.deviceId.isEmpty()) ? p.deviceId : (p.ip + ":" + p.port);
                                found.put(peerKey, p);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(2200, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
            probePool.shutdownNow();
        }

        try {
            udpThread.join(1000);
        } catch (Exception ignored) {}

        List<PeerInfo> result = new ArrayList<>();
        for (PeerInfo pi : found.values()) {
            if (pi != null && !isSelf(pi) && !isSelfIp(pi.ip)) {
                result.add(pi);
            }
        }
        return result;
    }

    public PeerInfo pingPeer(String hostInput, int defaultPort, int timeoutMs) {
        try {
            String clean = hostInput != null ? hostInput.trim() : "";
            if (clean.isEmpty()) return null;
            if (clean.toLowerCase().startsWith("http://")) clean = clean.substring(7);
            if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);

            int port = defaultPort;
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                clean = parts[0];
                if (parts.length > 1) {
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }

            URL url = new URL("http://" + clean + ":" + port + "/api/ping");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                String json = readStreamAsString(conn.getInputStream());
                PeerInfo info = parsePeerInfo(new JSONObject(json));
                if (info != null && "YYComicReader".equals(info.app)) {
                    info.ip = clean;
                    info.port = port;
                    return info;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private PeerInfo parsePeerInfo(JSONObject obj) {
        if (obj == null) return null;
        PeerInfo info = new PeerInfo();
        info.app = obj.optString("app", "");
        info.deviceId = obj.optString("deviceId", "");
        info.deviceName = obj.optString("deviceName", "未知设备");
        info.platform = obj.optString("platform", "");
        info.ip = obj.optString("ip", "");
        info.port = obj.optInt("port", DEFAULT_HTTP_PORT);
        info.comicCount = obj.optInt("comicCount", 0);
        info.requirePassword = obj.optBoolean("requirePassword", false);
        info.versionCode = obj.optInt("versionCode", 0);
        info.versionName = obj.optString("versionName", "");
        return info;
    }

    public ConnectResult connectToPeerWithHandshake(PeerInfo peer, String password, java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        ConnectResult res = new ConnectResult();
        res.peer = peer;
        if (peer == null || peer.ip == null || peer.ip.isEmpty() || isSelf(peer) || isSelfIp(peer.ip)) {
            res.ok = false;
            res.status = "error";
            res.message = (peer != null && (isSelf(peer) || isSelfIp(peer.ip))) ? "无法连接自身设备" : "目标设备无效";
            return res;
        }

        int port = peer.port > 0 ? peer.port : DEFAULT_HTTP_PORT;
        String myId = getDeviceId();
        String proposedToken = java.util.UUID.randomUUID().toString().replace("-", "");
        currentSessionToken = proposedToken;
        currentConnectedPeerId = peer.deviceId;
        try {
            String authUrl = "http://" + peer.ip + ":" + port + "/api/connect-auth?fromId="
                    + URLEncoder.encode(myId, "UTF-8")
                    + "&fromName=" + URLEncoder.encode(getDeviceName(), "UTF-8")
                    + "&fromPlatform=Android&fromPort=" + listeningPort
                    + "&sessionToken=" + proposedToken;
            if (password != null && !password.isEmpty()) {
                authUrl += "&password=" + URLEncoder.encode(password, "UTF-8");
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(authUrl).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                currentSessionToken = null;
                currentConnectedPeerId = null;
                res.ok = false;
                res.status = "error";
                res.message = "HTTP " + conn.getResponseCode();
                return res;
            }

            String authJson = readStreamAsString(conn.getInputStream());
            JSONObject authObj = new JSONObject(authJson);
            String status = authObj.optString("status", "");

            if ("accepted".equals(status)) {
                res.ok = true;
                res.status = "accepted";
                res.message = "连接成功";
                String st = authObj.optString("sessionToken", "");
                if (st == null || st.isEmpty()) st = proposedToken;
                peer.sessionToken = st;
                res.sessionToken = st;
                currentSessionToken = st;
                currentConnectedPeerId = peer.deviceId;
                if (onPeerConnectedListener != null) {
                    onPeerConnectedListener.onPeerConnected(peer);
                }
                return res;
            } else if ("blacklisted".equals(status)) {
                currentSessionToken = null;
                currentConnectedPeerId = null;
                res.ok = false;
                res.status = "blacklisted";
                res.message = "对方已将本机加入黑名单，拒绝连接";
                return res;
            } else if ("need_password".equals(status)) {
                currentSessionToken = null;
                currentConnectedPeerId = null;
                res.ok = false;
                res.status = "need_password";
                res.message = "对方设备已开启密码连接保护，请输入密码";
                return res;
            } else if ("wrong_password".equals(status)) {
                currentSessionToken = null;
                currentConnectedPeerId = null;
                res.ok = false;
                res.status = "wrong_password";
                res.message = "连接密码错误，请重新输入";
                return res;
            } else if ("need_confirm".equals(status)) {
                String reqId = java.util.UUID.randomUUID().toString().replace("-", "");
                String reqUrl = "http://" + peer.ip + ":" + port + "/api/connect-request?fromId="
                        + URLEncoder.encode(myId, "UTF-8")
                        + "&fromName=" + URLEncoder.encode(getDeviceName(), "UTF-8")
                        + "&fromPlatform=Android&fromPort=" + listeningPort
                        + "&reqId=" + reqId
                        + "&sessionToken=" + proposedToken;
                HttpURLConnection reqConn = (HttpURLConnection) new URL(reqUrl).openConnection();
                reqConn.setConnectTimeout(4000);
                reqConn.setReadTimeout(5000);
                reqConn.setRequestMethod("GET");
                if (reqConn.getResponseCode() != 200) {
                    currentSessionToken = null;
                    currentConnectedPeerId = null;
                    res.ok = false;
                    res.status = "error";
                    res.message = "发送配对申请失败";
                    return res;
                }

                // 轮询状态 (最多 45 次 x 800ms ≈ 36 秒)
                for (int i = 0; i < 45; i++) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        currentSessionToken = null;
                        currentConnectedPeerId = null;
                        res.ok = false;
                        res.status = "cancelled";
                        res.message = "已取消连接";
                        return res;
                    }
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException e) {
                        currentSessionToken = null;
                        currentConnectedPeerId = null;
                        res.ok = false;
                        res.status = "cancelled";
                        res.message = "已取消连接";
                        return res;
                    }
                    if (cancelFlag != null && cancelFlag.get()) {
                        currentSessionToken = null;
                        currentConnectedPeerId = null;
                        res.ok = false;
                        res.status = "cancelled";
                        res.message = "已取消连接";
                        return res;
                    }

                    String pollUrl = "http://" + peer.ip + ":" + port + "/api/connect-status?reqId=" + reqId;
                    HttpURLConnection pollConn = (HttpURLConnection) new URL(pollUrl).openConnection();
                    pollConn.setConnectTimeout(3000);
                    pollConn.setReadTimeout(3000);
                    pollConn.setRequestMethod("GET");
                    if (pollConn.getResponseCode() == 200) {
                        String pollJson = readStreamAsString(pollConn.getInputStream());
                        JSONObject pollObj = new JSONObject(pollJson);
                        String pollStatus = pollObj.optString("status", "");
                        if ("accepted".equals(pollStatus)) {
                            res.ok = true;
                            res.status = "accepted";
                            res.message = "对方已允许连接";
                            String st = pollObj.optString("sessionToken", "");
                            if (st == null || st.isEmpty()) st = proposedToken;
                            peer.sessionToken = st;
                            res.sessionToken = st;
                            currentSessionToken = st;
                            currentConnectedPeerId = peer.deviceId;
                            if (onPeerConnectedListener != null) {
                                onPeerConnectedListener.onPeerConnected(peer);
                            }
                            return res;
                        } else if ("rejected".equals(pollStatus)) {
                            currentSessionToken = null;
                            currentConnectedPeerId = null;
                            res.ok = false;
                            res.status = "rejected";
                            res.message = "对方拒绝了连接请求";
                            return res;
                        } else if ("blacklisted".equals(pollStatus)) {
                            currentSessionToken = null;
                            currentConnectedPeerId = null;
                            res.ok = false;
                            res.status = "blacklisted";
                            res.message = "对方已将本机加入黑名单，拒绝连接";
                            return res;
                        } else if ("timeout".equals(pollStatus)) {
                            currentSessionToken = null;
                            currentConnectedPeerId = null;
                            res.ok = false;
                            res.status = "timeout";
                            res.message = "等待对方确认超时";
                            return res;
                        }
                    }
                }
                currentSessionToken = null;
                currentConnectedPeerId = null;
                res.ok = false;
                res.status = "timeout";
                res.message = "等待对方确认超时";
                return res;
            } else {
                currentSessionToken = null;
                currentConnectedPeerId = null;
                res.ok = false;
                res.status = status;
                res.message = authObj.optString("message", "连接被拒绝");
                return res;
            }
        } catch (Exception e) {
            currentSessionToken = null;
            currentConnectedPeerId = null;
            res.ok = false;
            res.status = "error";
            res.message = "连接失败: " + e.getMessage();
            return res;
        }
    }

    public List<RemoteComic> fetchRemoteComics(String ip, int port) throws Exception {
        return fetchRemoteComics(ip, port, currentSessionToken, true);
    }

    public List<RemoteComic> fetchRemoteComics(String ip, int port, boolean notifyConnect) throws Exception {
        return fetchRemoteComics(ip, port, currentSessionToken, notifyConnect);
    }

    public List<RemoteComic> fetchRemoteComics(String ip, int port, String sessionToken, boolean notifyConnect) throws Exception {
        if (isSelfIp(ip)) {
            return new ArrayList<>();
        }
        String urlStr = "http://" + ip + ":" + port + "/api/comics";
        List<String> qParams = new ArrayList<>();
        qParams.add("fromId=" + URLEncoder.encode(getDeviceId(), "UTF-8"));
        if (notifyConnect) {
            int myCount = 0;
            try {
                List<ComicItem> active = comicListProvider != null ? comicListProvider.getActiveComics() : null;
                if (active != null) myCount = active.size();
            } catch (Exception ignored) {}
            qParams.add("peerName=" + URLEncoder.encode(getDeviceName(), "UTF-8"));
            qParams.add("peerPlatform=Android");
            qParams.add("peerPort=" + listeningPort);
            qParams.add("peerComics=" + myCount);
        }
        String effectiveToken = (sessionToken != null && !sessionToken.isEmpty()) ? sessionToken : currentSessionToken;
        if (effectiveToken != null && !effectiveToken.isEmpty()) {
            qParams.add("sessionToken=" + URLEncoder.encode(effectiveToken, "UTF-8"));
        }
        if (!qParams.isEmpty()) {
            urlStr += "?" + String.join("&", qParams);
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");

        if (conn.getResponseCode() == 401) {
            throw new Exception("session_expired");
        }
        if (conn.getResponseCode() != 200) {
            throw new Exception("HTTP " + conn.getResponseCode());
        }

        String json = readStreamAsString(conn.getInputStream());
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("comics");
        List<RemoteComic> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                RemoteComic rc = new RemoteComic();
                rc.id = c.optString("id");
                rc.title = c.optString("title");
                rc.pageCount = c.optInt("pageCount", 0);
                rc.readCount = c.optInt("readCount", 0);
                rc.lastReadTime = c.optLong("lastReadTime", 0);
                rc.lastReadPage = c.optInt("lastReadPage", 1);
                rc.importTime = c.optLong("importTime", 0);
                list.add(rc);
            }
        }
        return list;
    }

    public TransferResult pullComicsFromPeer(String ip, int port, List<String> comicIds) throws Exception {
        TransferResult result = new TransferResult();
        if (comicIds == null || comicIds.isEmpty()) return result;

        File baseSaveDir = new File(ShelfStorage.getTransferSaveDir(context));
        if (!baseSaveDir.exists()) baseSaveDir.mkdirs();

        int totalComics = comicIds.size();

        for (int cIdx = 0; cIdx < totalComics; cIdx++) {
            String comicId = comicIds.get(cIdx);
            String mUrl = "http://" + ip + ":" + port + "/api/comic-manifest?id=" + URLEncoder.encode(comicId, "UTF-8");
            HttpURLConnection mConn = (HttpURLConnection) new URL(mUrl).openConnection();
            mConn.setConnectTimeout(8000);
            mConn.setReadTimeout(15000);
            if (mConn.getResponseCode() != 200) continue;

            JSONObject manifest = new JSONObject(readStreamAsString(mConn.getInputStream()));
            String title = manifest.optString("title", "未命名漫画");

            // 检查本机是否已拥有同名漫画，若已拥有则跳过不下载
            if (hasLocalActiveComic(title)) {
                result.skippedCount++;
                result.skippedTitles.add(title);
                if (onTransferProgressListener != null) {
                    int pct = Math.round(((cIdx + 1f) / totalComics) * 100f);
                    onTransferProgressListener.onProgress(
                            "⏩ 跳过《" + title + "》(本机已拥有)",
                            pct,
                            "本机已拥有《" + title + "》，无需重复接收"
                    );
                }
                continue;
            }

            JSONArray pages = manifest.optJSONArray("pages");
            if (pages == null || pages.length() == 0) continue;

            File comicFolder = new File(baseSaveDir, sanitizeName(title));
            if (!comicFolder.exists()) comicFolder.mkdirs();

            int pageTotal = pages.length();
            for (int pIdx = 0; pIdx < pageTotal; pIdx++) {
                JSONObject pageObj = pages.getJSONObject(pIdx);
                int pageIndex = pageObj.optInt("index", pIdx);
                String fileName = pageObj.optString("fileName", String.format("%04d.jpg", pIdx + 1));

                int overallPct = Math.round(((cIdx + (float) (pIdx + 1) / pageTotal) / totalComics) * 100f);
                if (onTransferProgressListener != null) {
                    onTransferProgressListener.onProgress(
                            "📥 正在接收 (" + (cIdx + 1) + "/" + totalComics + ") 《" + title + "》",
                            overallPct,
                            "正在下载第 " + (pIdx + 1) + "/" + pageTotal + " 页 (" + fileName + ")..."
                    );
                }

                String pUrl = "http://" + ip + ":" + port + "/api/comic-page?id="
                        + URLEncoder.encode(comicId, "UTF-8")
                        + "&index=" + pageIndex
                        + "&comicIndex=" + (cIdx + 1)
                        + "&totalComics=" + totalComics
                        + "&pageTotal=" + pageTotal
                        + "&peerName=" + URLEncoder.encode(getDeviceName(), "UTF-8");
                HttpURLConnection pConn = (HttpURLConnection) new URL(pUrl).openConnection();
                pConn.setConnectTimeout(10000);
                pConn.setReadTimeout(30000);
                if (pConn.getResponseCode() == 200) {
                    File targetFile = new File(comicFolder, sanitizeName(fileName));
                    try (InputStream in = new BufferedInputStream(pConn.getInputStream());
                         FileOutputStream fos = new FileOutputStream(targetFile)) {
                        byte[] buf = new byte[32768];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            fos.write(buf, 0, r);
                        }
                    }
                }
            }

            result.transferredCount++;
            if (onComicReceivedListener != null) {
                onComicReceivedListener.onComicReceived(title, comicFolder);
            }
        }

        notifyRemoteBatchComplete(ip, port, "pull", result.transferredCount, result.skippedCount);
        return result;
    }

    public TransferResult pushComicsToPeer(String ip, int port, List<ComicItem> comics) throws Exception {
        TransferResult result = new TransferResult();
        if (comics == null || comics.isEmpty()) return result;

        // 先实时查询对方设备已拥有的漫画列表，若对方已拥有则直接跳过不传送
        Set<String> remoteOwnedSet = new HashSet<>();
        try {
            List<RemoteComic> peerComics = fetchRemoteComics(ip, port, false);
            for (RemoteComic rc : peerComics) {
                String k = normalizeTitleKey(rc.title);
                if (!k.isEmpty()) remoteOwnedSet.add(k);
            }
        } catch (Exception ignored) {}

        int totalComics = comics.size();

        for (int cIdx = 0; cIdx < totalComics; cIdx++) {
            ComicItem comic = comics.get(cIdx);
            String titleKey = normalizeTitleKey(comic.getTitle());
            if (!titleKey.isEmpty() && remoteOwnedSet.contains(titleKey)) {
                result.skippedCount++;
                result.skippedTitles.add(comic.getTitle());
                if (onTransferProgressListener != null) {
                    int pct = Math.round(((cIdx + 1f) / totalComics) * 100f);
                    onTransferProgressListener.onProgress(
                            "⏩ 跳过《" + comic.getTitle() + "》(对方已拥有)",
                            pct,
                            "对方设备已拥有《" + comic.getTitle() + "》，已跳过传送"
                    );
                }
                continue;
            }

            List<String> pages = comic.getPagePaths();
            if (pages == null || pages.isEmpty()) continue;

            int pageTotal = pages.size();
            boolean skippedByServer = false;
            for (int pIdx = 0; pIdx < pageTotal; pIdx++) {
                String pagePath = pages.get(pIdx);
                String fileName = extractPageFileName(pagePath, pIdx);
                byte[] pageBytes = readPageBytes(pagePath);
                if (pageBytes == null || pageBytes.length == 0) continue;

                int overallPct = Math.round(((cIdx + (float) (pIdx + 1) / pageTotal) / totalComics) * 100f);
                if (onTransferProgressListener != null) {
                    onTransferProgressListener.onProgress(
                            "📤 正在发送 (" + (cIdx + 1) + "/" + totalComics + ") 《" + comic.getTitle() + "》",
                            overallPct,
                            "正在上传第 " + (pIdx + 1) + "/" + pageTotal + " 页 (" + fileName + ")..."
                    );
                }

                String uploadUrl = "http://" + ip + ":" + port + "/api/upload-page?comicTitle="
                        + URLEncoder.encode(comic.getTitle(), "UTF-8")
                        + "&fileName=" + URLEncoder.encode(fileName, "UTF-8")
                        + "&index=" + pIdx
                        + "&total=" + pageTotal
                        + "&comicIndex=" + (cIdx + 1)
                        + "&totalComics=" + totalComics
                        + "&peerName=" + URLEncoder.encode(getDeviceName(), "UTF-8");

                HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(pageBytes.length);
                conn.setRequestProperty("Content-Type", "application/octet-stream");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(pageBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("上传失败 HTTP " + code);
                }
                try {
                    String respBody = readStreamAsString(conn.getInputStream());
                    if (respBody.contains("\"alreadyExists\":true")) {
                        skippedByServer = true;
                        result.skippedCount++;
                        result.skippedTitles.add(comic.getTitle());
                        if (!titleKey.isEmpty()) remoteOwnedSet.add(titleKey);
                        if (onTransferProgressListener != null) {
                            int pct = Math.round(((cIdx + 1f) / totalComics) * 100f);
                            onTransferProgressListener.onProgress(
                                    "⏩ 跳过《" + comic.getTitle() + "》(对方已拥有)",
                                    pct,
                                    "对方设备提示已拥有《" + comic.getTitle() + "》，已停止传送"
                            );
                        }
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (skippedByServer) {
                continue;
            }

            String completeUrl = "http://" + ip + ":" + port + "/api/upload-complete?comicTitle="
                    + URLEncoder.encode(comic.getTitle(), "UTF-8");
            HttpURLConnection cConn = (HttpURLConnection) new URL(completeUrl).openConnection();
            cConn.setConnectTimeout(8000);
            cConn.setReadTimeout(10000);
            cConn.setRequestMethod("POST");
            cConn.setDoOutput(true);
            byte[] emptyJson = "{}".getBytes(StandardCharsets.UTF_8);
            cConn.setFixedLengthStreamingMode(emptyJson.length);
            try (OutputStream os = cConn.getOutputStream()) {
                os.write(emptyJson);
            }
            cConn.getResponseCode();

            result.transferredCount++;
            if (!titleKey.isEmpty()) remoteOwnedSet.add(titleKey);
        }

        notifyRemoteBatchComplete(ip, port, "push", result.transferredCount, result.skippedCount);
        return result;
    }

    private void notifyRemoteBatchComplete(String ip, int port, String mode, int transferredCount, int skippedCount) {
        try {
            String batchUrl = "http://" + ip + ":" + port + "/api/batch-complete?mode=" + URLEncoder.encode(mode, "UTF-8")
                    + "&peerName=" + URLEncoder.encode(getDeviceName(), "UTF-8")
                    + "&transferredCount=" + transferredCount
                    + "&skippedCount=" + skippedCount;
            HttpURLConnection bConn = (HttpURLConnection) new URL(batchUrl).openConnection();
            bConn.setConnectTimeout(5000);
            bConn.setReadTimeout(5000);
            bConn.setRequestMethod("POST");
            bConn.setDoOutput(true);
            byte[] emptyJson = "{}".getBytes(StandardCharsets.UTF_8);
            bConn.setFixedLengthStreamingMode(emptyJson.length);
            try (OutputStream os = bConn.getOutputStream()) {
                os.write(emptyJson);
            }
            bConn.getResponseCode();
        } catch (Exception ignored) {}
    }

    public boolean renameRemoteComic(String ip, int port, String comicId, String oldTitle, String newTitle) {
        try {
            String url = "http://" + ip + ":" + port + "/api/comic-rename?id=" + URLEncoder.encode(comicId != null ? comicId : "", "UTF-8")
                    + "&title=" + URLEncoder.encode(oldTitle != null ? oldTitle : "", "UTF-8")
                    + "&newTitle=" + URLEncoder.encode(newTitle != null ? newTitle : "", "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setRemoteComicCoverFromPage(String ip, int port, String comicId, int pageIndex) {
        try {
            String url = "http://" + ip + ":" + port + "/api/comic-set-cover-page?id=" + URLEncoder.encode(comicId != null ? comicId : "", "UTF-8")
                    + "&pageIndex=" + pageIndex;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean resetRemoteComicCover(String ip, int port, String comicId) {
        try {
            String url = "http://" + ip + ":" + port + "/api/comic-reset-cover?id=" + URLEncoder.encode(comicId != null ? comicId : "", "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setRemoteComicCoverFile(String ip, int port, String comicId, byte[] imageBytes, String fileName) {
        try {
            String url = "http://" + ip + ":" + port + "/api/comic-set-cover-file?id=" + URLEncoder.encode(comicId != null ? comicId : "", "UTF-8")
                    + "&fileName=" + URLEncoder.encode(fileName != null ? fileName : "cover.jpg", "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(imageBytes.length);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(imageBytes);
                os.flush();
            }
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean deleteRemoteComic(String ip, int port, String comicId, String title) {
        try {
            String url = "http://" + ip + ":" + port + "/api/comic-delete?id=" + URLEncoder.encode(comicId != null ? comicId : "", "UTF-8")
                    + "&title=" + URLEncoder.encode(title != null ? title : "", "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================= 轻量级内嵌 HTTP 服务端 =========================
    private void acceptHttpLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                serverPool.execute(() -> handleHttpClient(client));
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void handleHttpClient(Socket client) {
        try (Socket sock = client) {
            sock.setSoTimeout(60000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            ByteArrayOutputStream headerBos = new ByteArrayOutputStream(1024);
            int b;
            int state = 0;
            while (headerBos.size() < 32768 && (b = in.read()) != -1) {
                headerBos.write(b);
                if ((state == 0 || state == 2) && b == '\r') state++;
                else if ((state == 1 || state == 3) && b == '\n') state++;
                else state = 0;
                if (state == 4) break;
            }
            if (state != 4) return;

            String headerStr = new String(headerBos.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = headerStr.split("\r\n");
            if (lines.length == 0) return;

            String[] reqParts = lines[0].split(" ");
            if (reqParts.length < 2) return;
            String method = reqParts[0].toUpperCase();
            String rawTarget = reqParts[1];

            long contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Long.parseLong(line.substring("content-length:".length()).trim());
                    } catch (Exception ignored) {}
                }
            }

            String path = rawTarget;
            String queryStr = "";
            int qIdx = rawTarget.indexOf('?');
            if (qIdx >= 0) {
                path = rawTarget.substring(0, qIdx);
                queryStr = rawTarget.substring(qIdx + 1);
            }
            Map<String, String> query = parseQuery(queryStr);

            if ("GET".equals(method) && "/api/ping".equalsIgnoreCase(path)) {
                String bestIp = matchBestLocalIpForRemote(sock.getInetAddress());
                writeJsonResponse(out, 200, buildSelfPeerJson(bestIp).toString());
            } else if ("/api/connect-auth".equalsIgnoreCase(path) || "api/connect-auth".equalsIgnoreCase(path)) {
                String fromId = query.getOrDefault("fromId", "");
                String fromName = query.getOrDefault("fromName", "未知设备");
                String fromPlatform = query.getOrDefault("fromPlatform", "未知");
                int fromPort = DEFAULT_HTTP_PORT;
                try {
                    fromPort = Integer.parseInt(query.getOrDefault("fromPort", String.valueOf(DEFAULT_HTTP_PORT)));
                } catch (Exception ignored) {}
                String password = query.getOrDefault("password", "");

                String remoteIp = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "";
                if (fromId.equalsIgnoreCase(getDeviceId()) || isSelfIp(remoteIp)) {
                    writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"rejected\",\"message\":\"无法连接本机自身设备\"}");
                    return;
                }
                if (isDeviceBlacklisted(fromId, remoteIp)) {
                    writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"blacklisted\",\"message\":\"设备已在黑名单中，拒绝连接\"}");
                    return;
                }
                String incomingToken = query.get("sessionToken");
                boolean isWhitelisted = !fromId.isEmpty() && isDeviceWhitelisted(fromId);
                boolean reqPass = isRequirePassword();
                String expectedPass = getConnectionPassword();

                if (isWhitelisted) {
                    currentSessionToken = (incomingToken != null && !incomingToken.isEmpty()) ? incomingToken : java.util.UUID.randomUUID().toString().replace("-", "");
                    currentConnectedPeerId = fromId;
                    PeerInfo peer = new PeerInfo();
                    peer.app = "YYComicReader";
                    peer.deviceId = fromId;
                    peer.deviceName = fromName;
                    peer.platform = fromPlatform;
                    peer.ip = remoteIp;
                    peer.port = fromPort;
                    peer.sessionToken = currentSessionToken;
                    if (onPeerConnectedListener != null) {
                        onPeerConnectedListener.onPeerConnected(peer);
                    }
                    writeJsonResponse(out, 200, "{\"ok\":true,\"status\":\"accepted\",\"sessionToken\":\"" + currentSessionToken + "\",\"message\":\"白名单免确认连接\"}");
                } else if (reqPass) {
                    if (password.isEmpty()) {
                        writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"need_password\",\"message\":\"需要输入连接密码\"}");
                    } else if (password.equals(expectedPass)) {
                        currentSessionToken = (incomingToken != null && !incomingToken.isEmpty()) ? incomingToken : java.util.UUID.randomUUID().toString().replace("-", "");
                        currentConnectedPeerId = fromId;
                        PeerInfo peer = new PeerInfo();
                        peer.app = "YYComicReader";
                        peer.deviceId = fromId;
                        peer.deviceName = fromName;
                        peer.platform = fromPlatform;
                        peer.ip = remoteIp;
                        peer.port = fromPort;
                        peer.sessionToken = currentSessionToken;
                        if (onPeerConnectedListener != null) {
                            onPeerConnectedListener.onPeerConnected(peer);
                        }
                        writeJsonResponse(out, 200, "{\"ok\":true,\"status\":\"accepted\",\"sessionToken\":\"" + currentSessionToken + "\",\"message\":\"密码验证通过\"}");
                    } else {
                        writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"wrong_password\",\"message\":\"密码错误\"}");
                    }
                } else {
                    writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"need_confirm\",\"message\":\"需要被连接方确认\"}");
                }
            } else if ("/api/connect-request".equalsIgnoreCase(path) || "api/connect-request".equalsIgnoreCase(path)) {
                String reqId = query.getOrDefault("reqId", java.util.UUID.randomUUID().toString().replace("-", ""));
                String fromId = query.getOrDefault("fromId", "");
                String fromName = query.getOrDefault("fromName", "未知设备");
                String fromPlatform = query.getOrDefault("fromPlatform", "");
                int fromPort = DEFAULT_HTTP_PORT;
                try {
                    fromPort = Integer.parseInt(query.getOrDefault("fromPort", String.valueOf(DEFAULT_HTTP_PORT)));
                } catch (Exception ignored) {}
                String remoteIp = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "";
                if (fromId.equalsIgnoreCase(getDeviceId()) || isSelfIp(remoteIp)) {
                    writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"rejected\",\"message\":\"无法连接本机自身设备\"}");
                    return;
                }
                if (isDeviceBlacklisted(fromId, remoteIp)) {
                    writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"blacklisted\",\"message\":\"设备已在黑名单中，拒绝连接\"}");
                    return;
                }
                String incomingToken = query.get("sessionToken");
                if (!fromId.isEmpty() && isDeviceWhitelisted(fromId)) {
                    currentSessionToken = (incomingToken != null && !incomingToken.isEmpty()) ? incomingToken : java.util.UUID.randomUUID().toString().replace("-", "");
                    currentConnectedPeerId = fromId;
                    PeerInfo peer = new PeerInfo();
                    peer.app = "YYComicReader";
                    peer.deviceId = fromId;
                    peer.deviceName = fromName;
                    peer.platform = fromPlatform;
                    peer.ip = remoteIp;
                    peer.port = fromPort;
                    peer.sessionToken = currentSessionToken;
                    if (onPeerConnectedListener != null) {
                        onPeerConnectedListener.onPeerConnected(peer);
                    }
                    writeJsonResponse(out, 200, "{\"ok\":true,\"status\":\"accepted\",\"sessionToken\":\"" + currentSessionToken + "\",\"message\":\"白名单免确认连接\"}");
                    return;
                }

                PairRequestState reqState = new PairRequestState();
                reqState.reqId = reqId;
                reqState.fromId = fromId;
                reqState.fromName = fromName;
                reqState.fromPlatform = fromPlatform;
                reqState.fromIp = remoteIp;
                reqState.fromPort = fromPort;
                reqState.status = "pending";
                reqState.sessionToken = (incomingToken != null && !incomingToken.isEmpty()) ? incomingToken : java.util.UUID.randomUUID().toString().replace("-", "");
                reqState.createdTime = System.currentTimeMillis();

                pairRequests.put(reqId, reqState);
                if (onPairRequestListener != null) {
                    onPairRequestListener.onPairRequestReceived(reqState);
                }

                JSONObject resp = new JSONObject();
                resp.put("ok", true);
                resp.put("status", "pending");
                resp.put("reqId", reqId);
                writeJsonResponse(out, 200, resp.toString());
            } else if ("/api/connect-status".equalsIgnoreCase(path) || "api/connect-status".equalsIgnoreCase(path)) {
                String reqId = query.getOrDefault("reqId", "");
                PairRequestState pollState = pairRequests.get(reqId);
                if (pollState != null) {
                    if (pollState.isExpired() || "timeout".equals(pollState.status)) {
                        pollState.status = "timeout";
                    }
                    JSONObject resp = new JSONObject();
                    resp.put("ok", true);
                    resp.put("status", pollState.status);
                    resp.put("sessionToken", pollState.sessionToken != null ? pollState.sessionToken : "");
                    writeJsonResponse(out, 200, resp.toString());
                } else {
                    writeJsonResponse(out, 404, "{\"ok\":false,\"status\":\"not_found\"}");
                }
            } else if ("/api/connect-respond".equalsIgnoreCase(path) || "api/connect-respond".equalsIgnoreCase(path)) {
                String reqId = query.getOrDefault("reqId", "");
                String action = query.getOrDefault("action", "reject").toLowerCase();
                boolean accept = action.equals("accept") || action.equals("1") || action.equals("true");

                PairRequestState respState = pairRequests.get(reqId);
                if (respState != null) {
                    if (respState.isExpired() || "timeout".equals(respState.status)) {
                        respState.status = "timeout";
                        writeJsonResponse(out, 200, "{\"ok\":false,\"status\":\"timeout\",\"error\":\"请求已超时失效\"}");
                        return;
                    }
                    respState.status = accept ? "accepted" : "rejected";
                    if (accept) {
                        currentSessionToken = java.util.UUID.randomUUID().toString().replace("-", "");
                        currentConnectedPeerId = respState.fromId;
                        respState.sessionToken = currentSessionToken;
                        PeerInfo peer = new PeerInfo();
                        peer.app = "YYComicReader";
                        peer.deviceId = respState.fromId;
                        peer.deviceName = respState.fromName;
                        peer.platform = respState.fromPlatform;
                        peer.ip = respState.fromIp;
                        peer.port = respState.fromPort;
                        peer.sessionToken = currentSessionToken;
                        if (onPeerConnectedListener != null) {
                            onPeerConnectedListener.onPeerConnected(peer);
                        }
                    }
                    JSONObject resp = new JSONObject();
                    resp.put("ok", true);
                    resp.put("status", respState.status);
                    resp.put("sessionToken", respState.sessionToken != null ? respState.sessionToken : "");
                    writeJsonResponse(out, 200, resp.toString());
                } else {
                    writeJsonResponse(out, 404, "{\"ok\":false,\"error\":\"Request not found\"}");
                }
            } else if ("/api/disconnect".equalsIgnoreCase(path) || "api/disconnect".equalsIgnoreCase(path)) {
                String fromId = query.getOrDefault("fromId", "");
                String fromName = query.getOrDefault("fromName", "");
                String remoteIp = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "";
                if (remoteIp.startsWith("/::ffff:")) remoteIp = remoteIp.substring(8);
                else if (remoteIp.startsWith("::ffff:")) remoteIp = remoteIp.substring(7);
                else if (remoteIp.startsWith("/")) remoteIp = remoteIp.substring(1);
                currentSessionToken = null;
                currentConnectedPeerId = null;
                if (onPeerDisconnectedListener != null) {
                    onPeerDisconnectedListener.onPeerDisconnected(fromId, fromName, remoteIp);
                }
                writeJsonResponse(out, 200, "{\"ok\":true}");
            } else if ("GET".equals(method) && ("/api/comic-progress".equalsIgnoreCase(path) || "api/comic-progress".equalsIgnoreCase(path))) {
                String id = query.get("id");
                String title = query.get("title");
                ComicItem target = findComicById(id);
                if (target == null && title != null) target = findComicById(title);
                if (target != null) {
                    int p = Math.max(1, target.getLastReadPage());
                    JSONObject resp = new JSONObject();
                    resp.put("ok", true);
                    resp.put("id", target.getId());
                    resp.put("lastReadPage", p);
                    resp.put("readCount", target.getReadCount());
                    writeJsonResponse(out, 200, resp.toString());
                } else {
                    writeJsonResponse(out, 404, "{\"ok\":false,\"error\":\"Comic not found\"}");
                }
            } else if (("POST".equals(method) || "GET".equals(method)) && ("/api/record-read".equalsIgnoreCase(path) || "api/record-read".equalsIgnoreCase(path))) {
                String id = query.get("id");
                String title = query.get("title");
                int page = 1;
                try { page = Integer.parseInt(query.get("page")); } catch (Exception ignored) {}
                boolean inc = "1".equals(query.get("inc")) || "true".equalsIgnoreCase(query.get("inc")) || query.get("inc") == null;
                if (onRemoteOperationListener != null) {
                    onRemoteOperationListener.onRecordRead(id, title, page, inc);
                }
                writeJsonResponse(out, 200, "{\"ok\":true}");
            } else if (("POST".equals(method) || "GET".equals(method)) && ("/api/comic-delete".equalsIgnoreCase(path) || "api/comic-delete".equalsIgnoreCase(path))) {
                String id = query.get("id");
                String title = query.get("title");
                boolean success = false;
                if (onRemoteOperationListener != null) {
                    success = onRemoteOperationListener.onDeleteComic(id, title);
                }
                if (success) {
                    writeJsonResponse(out, 200, "{\"ok\":true}");
                } else {
                    writeJsonResponse(out, 404, "{\"ok\":false,\"error\":\"Comic not found or failed to delete\"}");
                }
            } else if (("POST".equals(method) || "GET".equals(method)) && ("/api/comic-rename".equalsIgnoreCase(path) || "api/comic-rename".equalsIgnoreCase(path))) {
                String id = query.get("id");
                String title = query.get("title");
                String newTitle = query.get("newTitle");
                boolean success = false;
                if (onRemoteOperationListener != null && newTitle != null && !newTitle.trim().isEmpty()) {
                    success = onRemoteOperationListener.onRenameComic(id, title, newTitle.trim());
                }
                if (success) {
                    writeJsonResponse(out, 200, "{\"ok\":true,\"newTitle\":\"" + newTitle.trim() + "\"}");
                } else {
                    writeJsonResponse(out, 400, "{\"ok\":false,\"error\":\"Failed to rename comic\"}");
                }
            } else if (("POST".equals(method) || "GET".equals(method)) && ("/api/comic-set-cover-page".equalsIgnoreCase(path) || "api/comic-set-cover-page".equalsIgnoreCase(path))) {
                String id = query.get("id");
                int pageIndex = 0;
                try { pageIndex = Integer.parseInt(query.get("pageIndex")); } catch (Exception ignored) {}
                boolean success = false;
                if (onRemoteOperationListener != null) {
                    success = onRemoteOperationListener.onSetComicCoverPage(id, pageIndex);
                }
                if (success) {
                    writeJsonResponse(out, 200, "{\"ok\":true}");
                } else {
                    writeJsonResponse(out, 400, "{\"ok\":false,\"error\":\"Failed to set comic cover\"}");
                }
            } else if (("POST".equals(method) || "GET".equals(method)) && ("/api/comic-reset-cover".equalsIgnoreCase(path) || "api/comic-reset-cover".equalsIgnoreCase(path))) {
                String id = query.get("id");
                boolean success = false;
                if (onRemoteOperationListener != null) {
                    success = onRemoteOperationListener.onResetComicCover(id);
                }
                if (success) {
                    writeJsonResponse(out, 200, "{\"ok\":true}");
                } else {
                    writeJsonResponse(out, 400, "{\"ok\":false,\"error\":\"Failed to reset comic cover\"}");
                }
            } else if (("POST".equals(method) || "GET".equals(method)) && ("/api/comic-set-cover-file".equalsIgnoreCase(path) || "api/comic-set-cover-file".equalsIgnoreCase(path))) {
                String id = query.get("id");
                String ext = query.get("ext");
                if (ext == null || ext.isEmpty()) ext = ".jpg";
                boolean success = false;
                if (contentLength > 0 && id != null && !id.trim().isEmpty() && onRemoteOperationListener != null) {
                    byte[] imgBytes = new byte[(int) contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int r = in.read(imgBytes, totalRead, (int) (contentLength - totalRead));
                        if (r <= 0) break;
                        totalRead += r;
                    }
                    success = onRemoteOperationListener.onSetComicCoverFile(id, imgBytes, ext);
                }
                if (success) {
                    writeJsonResponse(out, 200, "{\"ok\":true}");
                } else {
                    writeJsonResponse(out, 400, "{\"ok\":false,\"error\":\"Failed to save custom cover file\"}");
                }
            } else if ("GET".equals(method) && "/api/comics".equalsIgnoreCase(path)) {
                String fromId = query.get("fromId");
                String sessionToken = query.get("sessionToken");
                String remoteIp = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "";
                if (isDeviceBlacklisted(fromId, remoteIp)) {
                    writeJsonResponse(out, 403, "{\"error\":\"Device blacklisted\"}");
                    return;
                }
                if (!isRequestAuthorized(fromId, remoteIp, sessionToken)) {
                    writeJsonResponse(out, 401, "{\"ok\":false,\"status\":\"unauthorized\",\"error\":\"session_expired\",\"message\":\"未建立连接或连接已失效，请重新连接\"}");
                    return;
                }

                List<ComicItem> active = comicListProvider.getActiveComics();
                JSONObject resp = new JSONObject();
                resp.put("deviceName", getDeviceName());
                resp.put("platform", "Android");
                JSONArray arr = new JSONArray();
                if (active != null) {
                    for (ComicItem c : active) {
                        JSONObject item = new JSONObject();
                        item.put("id", c.getId() != null ? c.getId() : c.getTitle());
                        item.put("title", c.getTitle());
                        item.put("pageCount", c.getPagePaths() != null ? c.getPagePaths().size() : 0);
                        item.put("readCount", c.getReadCount());
                        item.put("lastReadTime", c.getLastReadTime());
                        item.put("importTime", c.getImportTime());
                        arr.put(item);
                    }
                }
                resp.put("comics", arr);
                writeJsonResponse(out, 200, resp.toString());
            } else if ("GET".equals(method) && "/api/comic-cover".equalsIgnoreCase(path)) {
                String id = query.get("id");
                ComicItem target = findComicById(id);
                if (target == null) {
                    writeJsonResponse(out, 404, "{\"error\":\"Not found\"}");
                    return;
                }
                String coverPath = target.getCoverPath();
                if ((coverPath == null || coverPath.isEmpty()) && target.getPagePaths() != null && !target.getPagePaths().isEmpty()) {
                    coverPath = target.getPagePaths().get(0);
                }
                byte[] imgBytes = readPageBytes(coverPath);
                if (imgBytes == null) {
                    writeJsonResponse(out, 404, "{\"error\":\"Cover unreadable\"}");
                    return;
                }
                String header = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: " + imgBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.US_ASCII));
                out.write(imgBytes);
                out.flush();
            } else if ("GET".equals(method) && "/api/comic-manifest".equalsIgnoreCase(path)) {
                String fromId = query.get("fromId");
                String sessionToken = query.get("sessionToken");
                String remoteIp = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "";
                if (isDeviceBlacklisted(fromId, remoteIp)) {
                    writeJsonResponse(out, 403, "{\"error\":\"Device blacklisted\"}");
                    return;
                }
                if (!isRequestAuthorized(fromId, remoteIp, sessionToken)) {
                    writeJsonResponse(out, 401, "{\"ok\":false,\"status\":\"unauthorized\",\"error\":\"session_expired\",\"message\":\"未建立连接或连接已失效，请重新连接\"}");
                    return;
                }
                String id = query.get("id");
                ComicItem target = findComicById(id);
                if (target == null) {
                    writeJsonResponse(out, 404, "{\"error\":\"Not found\"}");
                    return;
                }
                JSONObject manifest = new JSONObject();
                manifest.put("id", target.getId() != null ? target.getId() : target.getTitle());
                manifest.put("title", target.getTitle());
                List<String> pages = target.getPagePaths();
                manifest.put("pageCount", pages != null ? pages.size() : 0);
                JSONArray pageArr = new JSONArray();
                if (pages != null) {
                    for (int i = 0; i < pages.size(); i++) {
                        String p = pages.get(i);
                        JSONObject pObj = new JSONObject();
                        pObj.put("index", i);
                        pObj.put("fileName", extractPageFileName(p, i));
                        pObj.put("size", 0);
                        pageArr.put(pObj);
                    }
                }
                manifest.put("pages", pageArr);
                writeJsonResponse(out, 200, manifest.toString());
            } else if ("GET".equals(method) && "/api/comic-page".equalsIgnoreCase(path)) {
                String fromId = query.get("fromId");
                String sessionToken = query.get("sessionToken");
                String remoteIp = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "";
                if (isDeviceBlacklisted(fromId, remoteIp)) {
                    writeJsonResponse(out, 403, "{\"error\":\"Device blacklisted\"}");
                    return;
                }
                if (!isRequestAuthorized(fromId, remoteIp, sessionToken)) {
                    writeJsonResponse(out, 401, "{\"ok\":false,\"status\":\"unauthorized\",\"error\":\"session_expired\",\"message\":\"未建立连接或连接已失效，请重新连接\"}");
                    return;
                }
                String id = query.get("id");
                int pageIdx = 0;
                try {
                    pageIdx = Integer.parseInt(query.get("index"));
                } catch (Exception ignored) {}

                ComicItem target = findComicById(id);
                if (target == null || target.getPagePaths() == null || pageIdx < 0 || pageIdx >= target.getPagePaths().size()) {
                    writeJsonResponse(out, 404, "{\"error\":\"Page not found\"}");
                    return;
                }

                byte[] imgBytes = readPageBytes(target.getPagePaths().get(pageIdx));
                if (imgBytes == null) {
                    writeJsonResponse(out, 404, "{\"error\":\"File unreadable\"}");
                    return;
                }

                String header = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: " + imgBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.US_ASCII));
                out.write(imgBytes);
                out.flush();

                int cIdx = 0;
                int totalComics = 0;
                int pageTotal = target.getPagePaths().size();
                try { cIdx = Integer.parseInt(query.getOrDefault("comicIndex", "0")); } catch (Exception ignored) {}
                try { totalComics = Integer.parseInt(query.getOrDefault("totalComics", "0")); } catch (Exception ignored) {}
                try { pageTotal = Integer.parseInt(query.getOrDefault("pageTotal", String.valueOf(pageTotal))); } catch (Exception ignored) {}
                String peerName = query.getOrDefault("peerName", "对方设备");
                if (totalComics > 0 && pageTotal > 0 && onTransferProgressListener != null) {
                    int cZero = Math.max(0, cIdx - 1);
                    int overallPct = Math.round(((cZero + (pageIdx + 1f) / pageTotal) / totalComics) * 100f);
                    onTransferProgressListener.onProgress(
                            "📤 正在向 " + peerName + " 传送 (" + Math.max(1, cIdx) + "/" + totalComics + ") 《" + target.getTitle() + "》",
                            overallPct,
                            "正在发送第 " + (pageIdx + 1) + "/" + pageTotal + " 页..."
                    );
                }
            } else if ("POST".equals(method) && "/api/upload-page".equalsIgnoreCase(path)) {
                String comicTitle = query.getOrDefault("comicTitle", "未命名漫画");
                String fileName = query.getOrDefault("fileName", "page.jpg");
                int idx = 0;
                int total = 1;
                int cIdx = 0;
                int totalComics = 0;
                try { idx = Integer.parseInt(query.getOrDefault("index", "0")); } catch (Exception ignored) {}
                try { total = Integer.parseInt(query.getOrDefault("total", "1")); } catch (Exception ignored) {}
                try { cIdx = Integer.parseInt(query.getOrDefault("comicIndex", "0")); } catch (Exception ignored) {}
                try { totalComics = Integer.parseInt(query.getOrDefault("totalComics", "0")); } catch (Exception ignored) {}
                String peerName = query.getOrDefault("peerName", "");

                // 如果本机已拥有该漫画（在第 0 页上传时拦截），排空请求体并返回 alreadyExists:true
                if (idx == 0 && hasLocalActiveComic(comicTitle)) {
                    long remaining = contentLength;
                    byte[] drainBuf = new byte[32768];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(drainBuf.length, remaining);
                        int r = in.read(drainBuf, 0, toRead);
                        if (r <= 0) break;
                        remaining -= r;
                    }
                    writeJsonResponse(out, 200, "{\"ok\":true,\"alreadyExists\":true}");
                    return;
                }

                File baseDir = new File(ShelfStorage.getTransferSaveDir(context));
                File comicDir = new File(baseDir, sanitizeName(comicTitle));
                if (!comicDir.exists()) comicDir.mkdirs();

                File targetFile = new File(comicDir, sanitizeName(fileName));
                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    long remaining = contentLength;
                    byte[] buf = new byte[32768];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int r = in.read(buf, 0, toRead);
                        if (r <= 0) break;
                        fos.write(buf, 0, r);
                        remaining -= r;
                    }
                }

                if (total > 0 && onTransferProgressListener != null) {
                    int pct;
                    String statusTitle;
                    String fromLabel = (peerName != null && !peerName.isEmpty()) ? ("自 " + peerName + " ") : "";
                    if (totalComics > 0) {
                        int cZero = Math.max(0, cIdx - 1);
                        pct = Math.round(((cZero + (idx + 1f) / total) / totalComics) * 100f);
                        statusTitle = "📥 正在接收" + fromLabel + "(" + Math.max(1, cIdx) + "/" + totalComics + ") 《" + comicTitle + "》";
                    } else {
                        pct = Math.round(((idx + 1f) / total) * 100f);
                        statusTitle = "📥 正在接收" + fromLabel + "《" + comicTitle + "》";
                    }
                    onTransferProgressListener.onProgress(
                            statusTitle,
                            pct,
                            "已接收第 " + (idx + 1) + "/" + total + " 页 (" + fileName + ")"
                    );
                }

                writeJsonResponse(out, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/api/upload-complete".equalsIgnoreCase(path)) {
                if (contentLength > 0) {
                    long remaining = contentLength;
                    byte[] buf = new byte[4096];
                    while (remaining > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (r <= 0) break;
                        remaining -= r;
                    }
                }
                String comicTitle = query.getOrDefault("comicTitle", "未命名漫画");
                if (hasLocalActiveComic(comicTitle)) {
                    writeJsonResponse(out, 200, "{\"ok\":true,\"alreadyExists\":true}");
                    return;
                }
                File comicDir = new File(ShelfStorage.getTransferSaveDir(context), sanitizeName(comicTitle));
                if (comicDir.exists() && onComicReceivedListener != null) {
                    onComicReceivedListener.onComicReceived(comicTitle, comicDir);
                }
                writeJsonResponse(out, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/api/batch-complete".equalsIgnoreCase(path)) {
                if (contentLength > 0) {
                    long remaining = contentLength;
                    byte[] buf = new byte[4096];
                    while (remaining > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (r <= 0) break;
                        remaining -= r;
                    }
                }
                String mode = query.getOrDefault("mode", "push");
                String peerName = query.getOrDefault("peerName", "对方设备");
                int transferredCount = 0;
                int skippedCount = 0;
                try { transferredCount = Integer.parseInt(query.getOrDefault("transferredCount", "0")); } catch (Exception ignored) {}
                try { skippedCount = Integer.parseInt(query.getOrDefault("skippedCount", "0")); } catch (Exception ignored) {}
                if (onBatchTransferCompleteListener != null) {
                    onBatchTransferCompleteListener.onBatchComplete(mode, peerName, transferredCount, skippedCount);
                }
                writeJsonResponse(out, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && ("/api/push-update".equalsIgnoreCase(path) || "api/push-update".equalsIgnoreCase(path))) {
                byte[] body = new byte[contentLength > 0 ? (int) contentLength : 0];
                if (contentLength > 0) {
                    int read = 0;
                    while (read < contentLength) {
                        int r = in.read(body, read, (int) (contentLength - read));
                        if (r <= 0) break;
                        read += r;
                    }
                }
                try {
                    String jsonStr = new String(body, "UTF-8");
                    org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                    ApkUpdateManager.UpdateInfo info = ApkUpdateManager.UpdateInfo.fromJson(obj);
                    int localCode = ApkUpdateManager.getLocalVersionCode(context);
                    // 核心要求：若非手动强制推送，当本地版本已是最新或更高时，绝不弹窗打扰用户
                    boolean force = obj.optBoolean("force", false);
                    if (info != null && (info.versionCode > localCode || force) && onUpdateReceivedListener != null) {
                        onUpdateReceivedListener.onUpdateReceived(info);
                    }
                } catch (Exception ignored) {}
                writeJsonResponse(out, 200, "{\"ok\":true}");
            } else if ("GET".equals(method) && ("/api/client-version".equalsIgnoreCase(path) || "api/client-version".equalsIgnoreCase(path))) {
                int vCode = ApkUpdateManager.getLocalVersionCode(context);
                String vName = ApkUpdateManager.getLocalVersionName(context);
                writeJsonResponse(out, 200, "{\"versionCode\":" + vCode + ",\"versionName\":\"" + vName + "\"}");
            } else if ("GET".equals(method) && ("/api/check-update".equalsIgnoreCase(path) || "api/check-update".equalsIgnoreCase(path))) {
                writeJsonResponse(out, 200, "{\"hasUpdate\":false}");
            } else {
                writeJsonResponse(out, 404, "{\"error\":\"Unknown endpoint\"}");
            }
        } catch (Exception ignored) {}
    }

    private ComicItem findComicById(String id) {
        if (id == null) return null;
        List<ComicItem> active = comicListProvider.getActiveComics();
        if (active == null) return null;
        for (ComicItem c : active) {
            if (id.equals(c.getId()) || id.equals(c.getTitle())) {
                return c;
            }
        }
        return null;
    }

    private String extractPageFileName(String pathOrUri, int index) {
        if (pathOrUri == null || pathOrUri.isEmpty()) {
            return String.format("%04d.jpg", index + 1);
        }
        if (pathOrUri.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(pathOrUri);
                try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIdx >= 0) {
                            String name = cursor.getString(nameIdx);
                            if (name != null && !name.isEmpty()) return name;
                        }
                    }
                }
                String lastSeg = uri.getLastPathSegment();
                if (lastSeg != null) {
                    int slash = lastSeg.lastIndexOf('/');
                    return slash >= 0 ? lastSeg.substring(slash + 1) : lastSeg;
                }
            } catch (Exception ignored) {}
            return String.format("%04d.jpg", index + 1);
        }
        return new File(pathOrUri).getName();
    }

    private byte[] readPageBytes(String pathOrUri) {
        if (pathOrUri == null || pathOrUri.isEmpty()) return null;
        try {
            InputStream in;
            if (pathOrUri.startsWith("content://")) {
                in = context.getContentResolver().openInputStream(Uri.parse(pathOrUri));
            } else {
                File f = new File(pathOrUri);
                if (!f.exists()) return null;
                in = new FileInputStream(f);
            }
            if (in == null) return null;
            try (InputStream input = new BufferedInputStream(in);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[32768];
                int r;
                while ((r = input.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                }
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> parseQuery(String queryStr) {
        Map<String, String> map = new HashMap<>();
        if (queryStr == null || queryStr.isEmpty()) return map;
        String[] pairs = queryStr.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String k = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    map.put(k, v);
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private static void writeJsonResponse(OutputStream out, int code, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : "Not Found";
        String header = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    public static String readStreamAsString(InputStream in) throws Exception {
        try (InputStream input = new BufferedInputStream(in);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = input.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) return "未命名漫画";
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? "未命名漫画" : cleaned;
    }
}
