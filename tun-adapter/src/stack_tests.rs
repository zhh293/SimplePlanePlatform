use super::*;
use smoltcp::phy::Loopback;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

struct Network {
    device: Loopback,
    iface: Interface,
    sockets: SocketSet<'static>,
    peer: SocketHandle,
    conn: ActiveConnection,
    time: i64,
}

impl Network {
    fn new(capacity: usize) -> (Self, SmolTcpStream) {
        let mut device = Loopback::new(Medium::Ip);
        let mut iface = Interface::new(
            SmolConfig::new(HardwareAddress::Ip),
            &mut device,
            SmolInstant::from_millis(0),
        );
        iface.update_ip_addrs(|a| a.push(IpCidr::new(IpAddress::v4(10, 0, 0, 1), 24)).unwrap());
        let mut sockets = SocketSet::new(vec![]);
        let socket = || {
            TcpSocket::new(
                tcp::SocketBuffer::new(vec![0; 65536]),
                tcp::SocketBuffer::new(vec![0; 65536]),
            )
        };
        let mut server = socket();
        server.listen(443).unwrap();
        let handle = sockets.add(server);
        let mut client = socket();
        client
            .connect(iface.context(), (IpAddress::v4(10, 0, 0, 1), 443), 30000)
            .unwrap();
        let peer = sockets.add(client);
        for t in 0..10 {
            iface.poll(SmolInstant::from_millis(t), &mut device, &mut sockets);
        }
        assert_eq!(
            sockets.get::<TcpSocket>(handle).state(),
            TcpState::Established
        );
        let (tx, stream_rx) = mpsc::channel(capacity);
        let (stream_tx, rx) = mpsc::channel(capacity);
        let (notify, _) = mpsc::channel(1);
        let conn = ActiveConnection {
            handle,
            tx: Some(tx),
            rx,
            conn_tuple: (Ipv4Addr::LOCALHOST, 30000, Ipv4Addr::LOCALHOST, 443),
            notify: notify.clone(),
            pending_write: None,
            write_closed: false,
            closing_since: None,
        };
        (
            Self {
                device,
                iface,
                sockets,
                peer,
                conn,
                time: 10,
            },
            SmolTcpStream::new(stream_tx, stream_rx, notify),
        )
    }
    fn poll(&mut self) {
        self.time += 100;
        self.iface.poll(
            SmolInstant::from_millis(self.time),
            &mut self.device,
            &mut self.sockets,
        );
    }
    fn pump(&mut self) -> bool {
        self.conn.pump(
            self.sockets.get_mut::<TcpSocket>(self.conn.handle),
            std::time::Instant::now(),
        )
    }
    fn peer_read(&mut self, output: &mut Vec<u8>) {
        let socket = self.sockets.get_mut::<TcpSocket>(self.peer);
        while socket.can_recv() {
            let mut buffer = vec![0; 4096];
            let n = socket.recv_slice(&mut buffer).unwrap();
            output.extend_from_slice(&buffer[..n]);
        }
    }
}

#[tokio::test]
async fn full_upload_queue_preserves_every_byte() {
    let (mut net, mut stream) = Network::new(1);
    let expected: Vec<u8> = (0..50000).map(|n| (n % 251) as u8).collect();
    net.sockets
        .get_mut::<TcpSocket>(net.peer)
        .send_slice(&expected)
        .unwrap();
    net.poll();
    assert!(net.pump());
    // Only one 16 KiB chunk can be consumed; the rest must stay in TCP RX.
    assert!(net.sockets.get::<TcpSocket>(net.conn.handle).can_recv());
    let mut received = vec![];
    for _ in 0..10 {
        while let Ok(StreamCommand::Data(data)) = stream.rx.try_recv() {
            received.extend(data);
        }
        assert!(net.pump());
        net.poll();
    }
    assert_eq!(received, expected);
}

#[tokio::test]
async fn partial_download_write_preserves_tail_and_order() {
    let (mut net, mut stream) = Network::new(2);
    let prefix = vec![1; 65535];
    net.sockets
        .get_mut::<TcpSocket>(net.conn.handle)
        .send_slice(&prefix)
        .unwrap();
    let tail: Vec<u8> = (0..32768).map(|n| (n % 251) as u8).collect();
    stream.write_all(&tail).await.unwrap();
    stream.write_all(b"end").await.unwrap();
    assert!(net.pump());
    assert_eq!(net.conn.pending_write.as_ref().unwrap().1, 1);
    let mut received = vec![];
    for _ in 0..300 {
        net.poll();
        net.peer_read(&mut received);
        assert!(net.pump());
    }
    let expected = [prefix.as_slice(), &tail, b"end"].concat();
    assert_eq!(received, expected);
    assert!(net.conn.pending_write.is_none());
}

#[tokio::test]
async fn full_queue_shutdown_waits_and_delivers_close_after_data() {
    let (tx, mut rx) = mpsc::channel(1);
    let (_other, stream_rx) = mpsc::channel(1);
    let (notify, _) = mpsc::channel(1);
    let mut stream = SmolTcpStream::new(tx, stream_rx, notify);
    stream.write_all(b"body").await.unwrap();
    let shutdown = stream.shutdown();
    tokio::pin!(shutdown);
    assert!(matches!(
        tokio_test::task::spawn(shutdown.as_mut()).poll(),
        std::task::Poll::Pending
    ));
    assert!(matches!(rx.recv().await, Some(StreamCommand::Data(data)) if data == b"body"));
    shutdown.await.unwrap();
    assert!(matches!(rx.recv().await, Some(StreamCommand::Close)));
    stream.shutdown().await.unwrap();
    assert!(stream.write_all(b"late").await.is_err());
}

#[tokio::test]
async fn zero_length_read_does_not_consume_and_eof_is_stable() {
    let (tx, _rx) = mpsc::channel(1);
    let (other, stream_rx) = mpsc::channel(2);
    let (notify, _) = mpsc::channel(1);
    let mut stream = SmolTcpStream::new(tx, stream_rx, notify);
    other
        .send(StreamCommand::Data(b"ok".to_vec()))
        .await
        .unwrap();
    assert_eq!(stream.read(&mut []).await.unwrap(), 0);
    let mut data = [0; 2];
    stream.read_exact(&mut data).await.unwrap();
    assert_eq!(&data, b"ok");
    drop(other);
    assert_eq!(stream.read(&mut data).await.unwrap(), 0);
    assert_eq!(stream.read(&mut data).await.unwrap(), 0);
}

#[tokio::test]
async fn peer_half_close_keeps_response_alive_and_fin_drains() {
    let (mut net, mut stream) = Network::new(2);
    let peer = net.sockets.get_mut::<TcpSocket>(net.peer);
    peer.send_slice(b"request").unwrap();
    peer.close();
    for _ in 0..10 {
        net.poll();
        assert!(net.pump());
    }
    let mut request = vec![];
    stream.read_to_end(&mut request).await.unwrap();
    assert_eq!(request, b"request");
    stream.write_all(b"response after EOF").await.unwrap();
    stream.shutdown().await.unwrap();
    drop(stream);
    let mut response = vec![];
    let mut ended = false;
    for _ in 0..400 {
        if !net.pump() {
            ended = true;
            break;
        }
        net.poll();
        net.peer_read(&mut response);
    }
    assert_eq!(response, b"response after EOF");
    assert!(ended);
    let handle = net.conn.handle;
    net.sockets.remove(handle);
    net.sockets.remove(net.peer);
    assert_eq!(net.sockets.iter().count(), 0);
}

#[tokio::test]
async fn reset_and_dropped_forwarder_are_reclaimable() {
    for reset in [false, true] {
        let (mut net, stream) = Network::new(1);
        if reset {
            net.sockets.get_mut::<TcpSocket>(net.peer).abort();
            net.poll();
        } else {
            drop(stream);
        }
        assert!(!net.pump());
        net.poll(); // final RST before remove
        net.sockets.remove(net.conn.handle);
        net.sockets.remove(net.peer);
        assert_eq!(net.sockets.iter().count(), 0);
    }
}

#[test]
fn repeated_pending_failures_release_socket_storage() {
    let mut sockets = SocketSet::new(vec![]);
    for _ in 0..1000 {
        let mut socket = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0; 65536]),
            tcp::SocketBuffer::new(vec![0; 65536]),
        );
        socket.listen(443).unwrap();
        let handle = sockets.add(socket);
        let now = std::time::Instant::now();
        let mut pending = vec![(handle, now - std::time::Duration::from_secs(6))];
        for retired in retire_pending(&mut sockets, &mut pending, now) {
            sockets.remove(retired);
        }
        assert!(pending.is_empty());
        assert_eq!(sockets.iter().count(), 0);
    }
}

#[tokio::test]
async fn two_megabyte_round_trip_through_smol_stream_and_real_socks5() {
    use tokio::net::TcpListener;
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut auth = [0; 3];
        socket.read_exact(&mut auth).await.unwrap();
        socket.write_all(&[5, 0]).await.unwrap();
        let mut request = [0; 10];
        socket.read_exact(&mut request).await.unwrap();
        socket
            .write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0])
            .await
            .unwrap();
        let (mut read, mut write) = socket.into_split();
        tokio::io::copy(&mut read, &mut write).await.unwrap();
        write.shutdown().await.unwrap();
    });
    let (mut net, stream) = Network::new(1);
    let relay = tokio::spawn(crate::socks5::proxy_tcp_stream(
        "1.2.3.4", 443, stream, addr,
    ));
    let payload: Vec<u8> = (0..2 * 1024 * 1024).map(|i| (i % 251) as u8).collect();
    let mut sent = 0;
    let mut received = vec![];
    let mut peer_closed = false;
    let start = std::time::Instant::now();
    loop {
        assert!(
            start.elapsed() < std::time::Duration::from_secs(15),
            "round trip stalled: sent {}, received {}",
            sent,
            received.len()
        );
        let peer = net.sockets.get_mut::<TcpSocket>(net.peer);
        if peer.can_send() && sent < payload.len() {
            sent += peer.send_slice(&payload[sent..]).unwrap();
        }
        if sent == payload.len() && !peer_closed {
            peer.close();
            peer_closed = true;
        }
        net.poll();
        net.peer_read(&mut received);
        if !net.pump() {
            break;
        }
        // Yield to the real localhost SOCKS5 peer; deliberately keep channel capacity 1.
        tokio::time::sleep(std::time::Duration::from_millis(1)).await;
    }
    assert_eq!(received, payload);
    tokio::time::timeout(std::time::Duration::from_secs(2), relay)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    server.await.unwrap();
    net.sockets.remove(net.conn.handle);
    net.sockets.remove(net.peer);
    assert_eq!(net.sockets.iter().count(), 0);
}

#[tokio::test]
async fn unacknowledged_final_close_has_bounded_retention() {
    let (mut net, mut stream) = Network::new(1);
    net.sockets.get_mut::<TcpSocket>(net.peer).close();
    for _ in 0..10 {
        net.poll();
        net.pump();
    }
    let mut data = vec![];
    stream.read_to_end(&mut data).await.unwrap();
    stream.shutdown().await.unwrap();
    assert!(net.pump());
    let later = std::time::Instant::now() + std::time::Duration::from_secs(31);
    assert!(!net
        .conn
        .pump(net.sockets.get_mut::<TcpSocket>(net.conn.handle), later));
}
