package me.creepermaxcz.mcbots;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.UnexpectedEncryptionException;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Bot extends Thread {

    private String nickname;
    private ProxyInfo proxy;
    private InetSocketAddress address;
    private ClientSession client;
    private boolean hasMainListener;

    private double lastX, lastY, lastZ = -1;
    private float lastYaw = 0;
    private float lastPitch = 0;

    private boolean connected;
    private volatile int entityId = -1;

    private boolean manualDisconnecting = false;

    private double originX, originZ;
    private int wanderRadius;
    private boolean wandering = false;
    private Timer wanderTimer;
    private int wanderTickCounter = 0;
    private double wanderVelY = 0;
    private int groundTicks = 0;

    // Star of David path: 12 vertices of the hexagram outline
    private static final int STAR_VERTEX_COUNT = 12;
    private double[] starX, starZ;
    private int starTargetIndex = 0;

    private static final String[] WANDER_COMMANDS = {
            "/help", "/list", "/spawn", "/tps", "/ping", "/stats", "/me is walking around"
    };

    public Bot(MinecraftProtocol protocol, InetSocketAddress address, ProxyInfo proxy) {
        this.nickname = protocol.getProfile().getName();
        this.address = address;
        this.proxy = proxy;

        Log.info("Creating bot", nickname);

        client = ClientNetworkSessionFactory.factory()
                .setAddress(address.getHostString(), address.getPort())
                .setProtocol(protocol)
                .setProxy(proxy)
                .create();

        SessionService sessionService = new SessionService();
        client.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, sessionService);
    }

    @Override
    public void run() {

        if (!Main.isMinimal()) {
            client.addListener(new SessionAdapter() {

                @Override
                public void packetReceived(Session session, Packet packet) {
                    if (packet instanceof ClientboundLoginPacket) {
                        connected = true;
                        entityId = ((ClientboundLoginPacket) packet).getEntityId();
                        Log.info(nickname + " connected");

                        if (Main.joinMessages.size() > 0) {
                            for (String msg : Main.joinMessages) {
                                sendChat(msg);

                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                    }
                    else if (packet instanceof ClientboundPlayerPositionPacket) {
                        ClientboundPlayerPositionPacket p = (ClientboundPlayerPositionPacket) packet;

                        lastX = p.getPosition().getX();
                        lastY = p.getPosition().getY();
                        lastZ = p.getPosition().getZ();

                        wanderVelY = 0;
                        groundTicks = 5;

                        client.send(new ServerboundAcceptTeleportationPacket(p.getId()));
                    }
                    else if (packet instanceof ClientboundPlayerCombatKillPacket){
                        if (Main.autoRespawnDelay >= 0) {
                            Log.info("Bot " + nickname + " died. Respawning in " + Main.autoRespawnDelay + " ms.");
                            new Timer().schedule(
                                    new TimerTask() {
                                        @Override
                                        public void run() {
                                            client.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
                                        }
                                    },
                                    Main.autoRespawnDelay
                            );

                        }
                    }
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    connected = false;
                    Log.info(nickname + " disconnected");

                    // Do not write disconnect reason if disconnected by command
                    if (!manualDisconnecting) {
                        // Fix broken reason string by finding the content with regex
                        Pattern pattern = Pattern.compile("content=\"(.*?)\"");
                        Matcher matcher = pattern.matcher(String.valueOf(event.getReason()));

                        StringBuilder reason = new StringBuilder();
                        while (matcher.find()) {
                            reason.append(matcher.group(1));
                        }

                        Log.info(" -> " + reason.toString());

                        if(event.getCause() != null) {
                            event.getCause().printStackTrace();

                            if (event.getCause() instanceof UnexpectedEncryptionException) {
                                Log.warn("Server is running in online (premium) mode. Please use the -o option to use online mode bot.");
                                System.exit(1);
                            }
                        }
                        Log.info();
                    }

                    Main.removeBot(Bot.this);

                    Thread.currentThread().interrupt();
                }
            });
        }
        client.connect();
    }

    public void sendChat(String text) {
        // Send command
        if (text.startsWith("/")) {
            client.send(new ServerboundChatCommandPacket(
                    text.substring(1)
            ));
        } else {
            // Send chat message
            // From 1.19.1 or 1.19, the ServerboundChatPacket needs timestamp, salt and signed signature to generate packet.
            // tmpSignature will provide an empty byte array that can pretend it as signature.
            // salt is set 0 since this is offline server and no body will check it.

            client.send(new ServerboundChatPacket(
                    text,
                    Instant.now().toEpochMilli(),
                    0L,
                    null,
                    0,
                    new BitSet(),
                    0
            ));
        }
    }

    public String getNickname() {
        return nickname;
    }

    public void registerMainListener() {
        hasMainListener = true;
        if (Main.isMinimal()) return;
        client.addListener(new MainListener(nickname));
    }

    public boolean hasMainListener() {
        return hasMainListener;
    }

    public void fallDown()
    {
        if (connected && lastY > 0) {
            move(0, -0.5, 0);
        }
    }

    public void move(double x, double y, double z)
    {
        lastX += x;
        lastY += y;
        lastZ += z;
        moveTo(lastX, lastY, lastZ);
    }

    public void moveTo(double x, double y, double z)
    {
        client.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, lastYaw, lastPitch));
    }

    public void moveTo(double x, double y, double z, float yaw, float pitch)
    {
        lastYaw = yaw;
        lastPitch = pitch;
        client.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yaw, pitch));
    }

    public void startWander(int radius) {
        stopWander();
        this.wanderRadius = radius;
        this.originX = lastX;
        this.originZ = lastZ;

        // Compute 12 vertices of the Star of David (hexagram) outline.
        // Alternating outer tips (at distance radius) and inner concavity
        // points (at distance radius / sqrt(3)), spaced 30 degrees apart.
        double innerRadius = radius / Math.sqrt(3);
        starX = new double[STAR_VERTEX_COUNT];
        starZ = new double[STAR_VERTEX_COUNT];
        for (int i = 0; i < STAR_VERTEX_COUNT; i++) {
            double angle = Math.PI / 2 - i * Math.PI / 6;
            double r = (i % 2 == 0) ? radius : innerRadius;
            starX[i] = originX + r * Math.cos(angle);
            starZ[i] = originZ - r * Math.sin(angle);
        }
        starTargetIndex = 0;

        wandering = true;
        wanderTickCounter = 0;
        wanderVelY = 0;
        groundTicks = 5;
        wanderTimer = new Timer(true);
        wanderTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!connected || !wandering) return;
                try {
                    // Minecraft walking speed: ~4.317 blocks/sec
                    // Tick interval: 50ms (20 ticks/sec)
                    // Step per tick: 4.317 / 20 ≈ 0.216 blocks
                    double stepPerTick = 0.216;

                    // Move toward the current target vertex of the star
                    double targetX = starX[starTargetIndex];
                    double targetZ = starZ[starTargetIndex];
                    double dx = targetX - lastX;
                    double dz = targetZ - lastZ;
                    double dist = Math.sqrt(dx * dx + dz * dz);

                    double newX, newZ;
                    if (dist <= stepPerTick) {
                        // Reached the vertex, advance to the next one
                        newX = targetX;
                        newZ = targetZ;
                        starTargetIndex = (starTargetIndex + 1) % STAR_VERTEX_COUNT;
                    } else {
                        // Walk toward the target
                        double dirX = dx / dist;
                        double dirZ = dz / dist;
                        newX = lastX + dirX * stepPerTick;
                        newZ = lastZ + dirZ * stepPerTick;
                    }

                    // Yaw faces the direction of movement
                    double moveDirX = newX - lastX;
                    double moveDirZ = newZ - lastZ;
                    float yaw = (float) Math.toDegrees(Math.atan2(-moveDirX, moveDirZ));

                    lastX = newX;
                    lastZ = newZ;

                    // Apply gravity to simulate walking on ground instead of flying.
                    // After a server position confirmation, the bot stays on ground for a
                    // grace period. Once expired, gravity kicks in until the server sends
                    // a new position correction (which resets the grace period).
                    boolean currentlyOnGround;
                    if (groundTicks > 0) {
                        groundTicks--;
                        currentlyOnGround = true;
                    } else {
                        wanderVelY = (wanderVelY - 0.08) * 0.98;
                        lastY += wanderVelY;
                        currentlyOnGround = false;
                    }

                    client.send(new ServerboundMovePlayerPosRotPacket(currentlyOnGround, false, lastX, lastY, lastZ, yaw, 0));
                    lastYaw = yaw;
                    lastPitch = 0;

                    wanderTickCounter++;

                    // Swing arm every ~5 ticks (4 times/sec)
                    if (wanderTickCounter % 5 == 0) {
                        client.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
                    }

                    // Send interact packet every ~7 ticks (~3 times/sec) with a random entity id
                    // Server ignores invalid entity IDs; this is intentional to generate extra packets
                    if (wanderTickCounter % 7 == 0) {
                        int fakeEntityId = ThreadLocalRandom.current().nextInt(1, 1000);
                        // Avoid interacting with self which causes server to kick the bot
                        if (fakeEntityId != entityId) {
                            client.send(new ServerboundInteractPacket(fakeEntityId, InteractAction.ATTACK, false));
                        }
                    }

                    // Use item every ~11 ticks (~2 times/sec)
                    // Parameters: hand, sequence number, yaw rotation, pitch rotation
                    if (wanderTickCounter % 11 == 0) {
                        client.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, 0, yaw, 0));
                    }

                    // Send a random command every ~600 ticks (~30 sec)
                    if (wanderTickCounter % 600 == 0) {
                        String cmd = WANDER_COMMANDS[ThreadLocalRandom.current().nextInt(WANDER_COMMANDS.length)];
                        sendChat(cmd);
                    }
                } catch (Exception ignored) {
                    // Prevent timer from dying on transient errors
                }
            }
        }, 0, 50);
    }

    public void stopWander() {
        wandering = false;
        if (wanderTimer != null) {
            wanderTimer.cancel();
            wanderTimer = null;
        }
    }

    public boolean isWandering() {
        return wandering;
    }

    public boolean isConnected() {
        return connected;
    }

    public void disconnect()
    {
        stopWander();
        manualDisconnecting = true;
        client.disconnect("Leaving");
    }
}
