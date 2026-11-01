import com.proxy.common.exchange.ExchangeServer;
import com.proxy.common.model.URL;
import com.proxy.exchange.header.HeaderExchanger;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import io.netty.channel.nio.NioEventLoopGroup;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Loopback fixture using the production exchanger, encryption and outbound dispatcher. */
public final class AndroidUdpFixture {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        NioEventLoopGroup workers = new NioEventLoopGroup(2);
        DispatchInvoker dispatch = new DispatchInvoker(Executors.newFixedThreadPool(4), new OutboundConnector(workers, 1000), 1500);
        URL url = new URL("proxy", "127.0.0.1", port)
                .addParameter("transport", args[1]).addParameter("cipher", args[2])
                .addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                .addParameter("workerThreads", "2").addParameter("bossThreads", "1")
                .addParameter("http3.certificateFile", args[3] + "/server.pem")
                .addParameter("http3.privateKeyFile", args[3] + "/server.key");
        ExchangeServer server = new HeaderExchanger().bind(url, dispatch);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dispatch.shutdown(); server.close(); workers.shutdownGracefully();
        }));
        Path metrics = Paths.get(args[4]);
        while (true) {
            Files.write(metrics, String.valueOf(dispatch.getSessionManager().activeCount()).getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(100);
        }
    }
}
