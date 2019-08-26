package net.novaplay.netty;

import net.novaplay.netty.packet.Packet;
import net.novaplay.netty.packet.defaultpackets.DisconnectPacket;
import net.novaplay.netty.packet.defaultpackets.ErrorPacket;
import net.novaplay.netty.packet.defaultpackets.PacketTransferPacket;
import net.novaplay.netty.packet.defaultpackets.SetNamePacket;
import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.List;

public abstract class PacketHandler {
    public static List<Class<? extends Packet>> PACKETS = new ArrayList<>();
    public static ArrayList<Packet> packetsToSend = new ArrayList<>();

    public PacketHandler() {
        PACKETS.add(DisconnectPacket.class);
        PACKETS.add(SetNamePacket.class);
        PACKETS.add(PacketTransferPacket.class);
        PACKETS.add(ErrorPacket.class);
        registerPackets();
    }

    public static void sendPacketDirectly(Packet packet, Channel channel) {
        if(channel == null) {
            return;
        }
        channel.writeAndFlush(packet, channel.voidPromise());
    }

    public void sendPacket(Packet packet, Channel channel) {
        if( channel == null ) {
            return;
        }
        channel.writeAndFlush(packet, channel.voidPromise());
    }

    public void sendPacket(Packet packet) {
        if(NettyHandler.getInstance().getTypes() == NettyHandler.types.CLIENT) {
            if(NettyHandler.getInstance().getNettyClient().getChannel() == null) {
                packetsToSend.add(packet);
                return;
            }
            sendPacket(packet, NettyHandler.getInstance().getNettyClient().getChannel());
        } else {
            if(NettyHandler.getClients().size() == 0) {
                packetsToSend.add(packet);
                return;
            }
            for(Channel channel : NettyHandler.getClients().values()) {
                sendPacket(packet, channel);
            }
        }
    }

    public abstract void incomingPacket(Packet packet, Channel channel);

    public abstract void registerPackets();

    public void registerPacket(Class<? extends Packet> packet) {
        if(PacketHandler.PACKETS.contains(packet)) {
            return;
        }
        PacketHandler.PACKETS.add(packet);
    }
}