package fr.nagromc86.zapette;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Pilote une TV Samsung (Tizen 2016+) sur le réseau local :
 *  - canal télécommande WebSocket (wss://IP:8002) pour les touches,
 *  - lancement d'application par ID (REST http://IP:8001/api/v2/applications/<id>),
 *  - liste des applis installées (ed.installedApp.get),
 *  - allumage par Wake-on-LAN (paquet magique UDP),
 *  - Art Mode (canal com.samsung.art-app) — selon modèle.
 * Le certificat de la TV est auto-signé : on fait confiance au périphérique LAN.
 */
@CapacitorPlugin(name = "SamsungRemote")
public class SamsungRemotePlugin extends Plugin {

    private OkHttpClient http;
    private WebSocket control;
    private String controlIp;
    private volatile boolean controlReady;
    private volatile String newToken;
    private volatile JSONObject appsResult;
    private volatile CountDownLatch appsLatch;

    private OkHttpClient http() {
        if (http == null) {
            try {
                final X509TrustManager tm = new X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                };
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{ tm }, new java.security.SecureRandom());
                http = new OkHttpClient.Builder()
                        .sslSocketFactory(ssl.getSocketFactory(), tm)
                        .hostnameVerifier(new HostnameVerifier() { public boolean verify(String h, SSLSession s) { return true; } })
                        .pingInterval(20, TimeUnit.SECONDS)
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(0, TimeUnit.MILLISECONDS)
                        .build();
            } catch (Exception e) {
                http = new OkHttpClient();
            }
        }
        return http;
    }

    private void closeControl() {
        try { if (control != null) control.close(1000, "bye"); } catch (Exception e) {}
        control = null; controlReady = false;
    }

    /** Ouvre (ou réutilise) le canal télécommande ; attend l'autorisation jusqu'à 10 s. */
    private synchronized boolean ensureControl(String ip, String token) {
        if (control != null && controlReady && ip.equals(controlIp)) return true;
        closeControl();
        controlIp = ip;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        String name = Base64.encodeToString("TV Chap Simple".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String url = "wss://" + ip + ":8002/api/v2/channels/samsung.remote.control?name=" + name
                + (token != null && !token.isEmpty() ? "&token=" + token : "");
        control = http().newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject o = new JSONObject(text);
                    String ev = o.optString("event", "");
                    if ("ms.channel.connect".equals(ev)) {
                        JSONObject data = o.optJSONObject("data");
                        if (data != null) {
                            String tk = data.optString("token", "");
                            if (!tk.isEmpty()) newToken = tk;
                        }
                        controlReady = true; ok[0] = true; latch.countDown();
                    } else if ("ms.channel.unauthorized".equals(ev) || "ms.channel.timeOut".equals(ev)) {
                        latch.countDown();
                    } else if ("ed.installedApp.get".equals(ev)) {
                        appsResult = o;
                        if (appsLatch != null) appsLatch.countDown();
                    }
                } catch (Exception e) {}
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) { controlReady = false; latch.countDown(); }
            @Override public void onClosed(WebSocket ws, int c, String reason) { controlReady = false; }
        });
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) {}
        return ok[0];
    }

    private static String keyCmd(String key) {
        return "{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"Click\",\"DataOfCmd\":\""
                + key + "\",\"Option\":\"false\",\"TypeOfRemote\":\"SendRemoteKey\"}}";
    }

    @PluginMethod
    public void connect(final PluginCall call) {
        final String ip = call.getString("ip", "");
        final String token = call.getString("token", "");
        if (ip.isEmpty()) { call.reject("ip manquante"); return; }
        new Thread(new Runnable() { public void run() {
            boolean ok = ensureControl(ip, token);
            JSObject res = new JSObject();
            res.put("token", newToken != null && !newToken.isEmpty() ? newToken : token);
            // infos périphérique (nom + MAC Wi-Fi) via REST
            try {
                Request req = new Request.Builder().url("http://" + ip + ":8001/api/v2/").build();
                Response r = http().newCall(req).execute();
                if (r.body() != null) {
                    JSONObject info = new JSONObject(r.body().string());
                    JSONObject dev = info.optJSONObject("device");
                    if (dev != null) {
                        res.put("name", dev.optString("name", ""));
                        res.put("mac", dev.optString("wifiMac", ""));
                    }
                }
                r.close();
            } catch (Exception e) {}
            if (ok || res.getString("token") != null) call.resolve(res);
            else call.reject("connexion/appairage impossible");
        }}).start();
    }

    @PluginMethod
    public void key(final PluginCall call) {
        final String ip = call.getString("ip", "");
        final String token = call.getString("token", "");
        final String k = call.getString("key", "");
        if (ip.isEmpty() || k.isEmpty()) { call.reject("paramètres manquants"); return; }
        new Thread(new Runnable() { public void run() {
            if (!ensureControl(ip, token)) { call.reject("non connectée"); return; }
            try { control.send(keyCmd(k)); call.resolve(); }
            catch (Exception e) { call.reject("envoi échoué"); }
        }}).start();
    }

    @PluginMethod
    public void launch(final PluginCall call) {
        final String ip = call.getString("ip", "");
        final String appId = call.getString("appId", "");
        if (ip.isEmpty() || appId.isEmpty()) { call.reject("paramètres manquants"); return; }
        new Thread(new Runnable() { public void run() {
            try {
                Request req = new Request.Builder()
                        .url("http://" + ip + ":8001/api/v2/applications/" + appId)
                        .post(RequestBody.create(new byte[0], MediaType.parse("application/json")))
                        .build();
                Response r = http().newCall(req).execute();
                int code = r.code(); r.close();
                if (code >= 200 && code < 300) call.resolve();
                else call.reject("réponse TV " + code);
            } catch (Exception e) { call.reject("lancement impossible"); }
        }}).start();
    }

    @PluginMethod
    public void listApps(final PluginCall call) {
        final String ip = call.getString("ip", "");
        final String token = call.getString("token", "");
        if (ip.isEmpty()) { call.reject("ip manquante"); return; }
        new Thread(new Runnable() { public void run() {
            if (!ensureControl(ip, token)) { call.reject("non connectée"); return; }
            appsResult = null; appsLatch = new CountDownLatch(1);
            try {
                control.send("{\"method\":\"ms.channel.emit\",\"params\":{\"data\":\"\",\"event\":\"ed.installedApp.get\",\"to\":\"host\"}}");
                appsLatch.await(6, TimeUnit.SECONDS);
            } catch (Exception e) {}
            JSObject res = new JSObject();
            JSONArray out = new JSONArray();
            try {
                if (appsResult != null) {
                    JSONObject data = appsResult.optJSONObject("data");
                    JSONArray arr = data != null ? data.optJSONArray("data") : null;
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.optJSONObject(i);
                        if (a == null) continue;
                        JSObject row = new JSObject();
                        row.put("appId", a.optString("appId", ""));
                        row.put("name", a.optString("name", ""));
                        out.put(row);
                    }
                }
            } catch (Exception e) {}
            res.put("apps", out);
            call.resolve(res);
        }}).start();
    }

    @PluginMethod
    public void wol(final PluginCall call) {
        final String mac = call.getString("mac", "");
        if (mac.isEmpty()) { call.reject("mac manquante"); return; }
        new Thread(new Runnable() { public void run() {
            try {
                String[] parts = mac.split("[:\\-]");
                if (parts.length != 6) { call.reject("mac invalide"); return; }
                byte[] m = new byte[6];
                for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(parts[i], 16);
                byte[] pkt = new byte[102];
                for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
                for (int i = 6; i < 102; i += 6) System.arraycopy(m, 0, pkt, i, 6);
                DatagramSocket sock = new DatagramSocket();
                sock.setBroadcast(true);
                InetAddress bcast = InetAddress.getByName("255.255.255.255");
                sock.send(new DatagramPacket(pkt, pkt.length, bcast, 9));
                sock.send(new DatagramPacket(pkt, pkt.length, bcast, 7));
                sock.close();
                call.resolve();
            } catch (Exception e) { call.reject("WoL échoué"); }
        }}).start();
    }

    @PluginMethod
    public void artMode(final PluginCall call) {
        final String ip = call.getString("ip", "");
        final String token = call.getString("token", "");
        final boolean on = call.getBoolean("on", Boolean.TRUE);
        if (ip.isEmpty()) { call.reject("ip manquante"); return; }
        new Thread(new Runnable() { public void run() {
            String name = Base64.encodeToString("TV Chap Simple".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String url = "wss://" + ip + ":8002/api/v2/channels/com.samsung.art-app?name=" + name
                    + (token != null && !token.isEmpty() ? "&token=" + token : "");
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] sent = {false};
            final WebSocket[] holder = new WebSocket[1];
            holder[0] = http().newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    try {
                        if (new JSONObject(text).optString("event", "").startsWith("ms.channel.")) {
                            String req = "{\"request\":\"set_artmode_status\",\"value\":\"" + (on ? "on" : "off")
                                    + "\",\"id\":\"zapette\"}";
                            String msg = "{\"method\":\"ms.channel.emit\",\"params\":{\"event\":\"art_app_request\",\"to\":\"host\",\"data\":"
                                    + JSONObject.quote(req) + "}}";
                            ws.send(msg);
                            sent[0] = true; latch.countDown();
                        }
                    } catch (Exception e) {}
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response r) { latch.countDown(); }
            });
            try { latch.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) {}
            try { if (holder[0] != null) holder[0].close(1000, "bye"); } catch (Exception e) {}
            if (sent[0]) call.resolve(); else call.reject("Art Mode indisponible");
        }}).start();
    }
}
