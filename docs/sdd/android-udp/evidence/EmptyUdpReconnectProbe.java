import java.net.*;
import java.nio.*;
import java.nio.channels.*;
class EmptyUdpReconnectProbe {
  public static void main(String[] args) throws Exception {
    for(boolean reconnect : new boolean[]{true,false}) {
      try(DatagramSocket peer = new DatagramSocket(0,InetAddress.getLoopbackAddress()); DatagramChannel socket=DatagramChannel.open()) {
        peer.setSoTimeout(1000);
        InetSocketAddress target=new InetSocketAddress("127.0.0.1",peer.getLocalPort());
        socket.bind(new InetSocketAddress(0)); socket.connect(target); socket.disconnect();
        socket.send(ByteBuffer.allocate(0),target);
        DatagramPacket packet=new DatagramPacket(new byte[8],8); peer.receive(packet);
        peer.send(new DatagramPacket(new byte[0],0,packet.getSocketAddress()));
        Thread.sleep(20);
        if(reconnect) socket.connect(target);
        socket.configureBlocking(false);
        System.out.println("reconnect="+reconnect+" empty_reply_received="+(socket.receive(ByteBuffer.allocate(8))!=null));
      }
    }
  }
}
