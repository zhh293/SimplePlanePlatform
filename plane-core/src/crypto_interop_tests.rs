//! Opt-in end-to-end test against an actual Java proxy-remote and TCP echo target.
use crate::crypto::Cipher;
use crate::outbound::{OutboundConfig, OutboundConnection};
use crate::proxy_proto::MessageType;
use std::time::Duration;

#[tokio::test]
#[ignore = "requires CRYPTO_TEST_PORT, CRYPTO_TEST_SUITE and CRYPTO_TEST_KEY_FILE"]
async fn crypto_remote_echo() {
    let host = std::env::var("CRYPTO_TEST_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned());
    let port = std::env::var("CRYPTO_TEST_PORT").unwrap().parse().unwrap();
    let suite = std::env::var("CRYPTO_TEST_SUITE").unwrap();
    let key = std::fs::read_to_string(std::env::var("CRYPTO_TEST_KEY_FILE").unwrap()).unwrap();
    let target_port = std::env::var("CRYPTO_ECHO_PORT")
        .unwrap_or_else(|_| "19101".to_owned())
        .parse()
        .unwrap();
    let rounds = std::env::var("CRYPTO_TEST_ROUNDS")
        .unwrap_or_else(|_| "128".to_owned())
        .parse::<usize>()
        .unwrap();
    let payload = std::env::var("CRYPTO_TEST_PAYLOAD")
        .unwrap_or_else(|_| "16385".to_owned())
        .parse::<usize>()
        .unwrap();
    let timeout = std::env::var("CRYPTO_TEST_TIMEOUT_SECONDS")
        .unwrap_or_else(|_| "90".to_owned())
        .parse::<u64>()
        .unwrap();
    tokio::time::timeout(Duration::from_secs(timeout), async {
        let io = tokio::net::TcpStream::connect((host.as_str(), port))
            .await
            .unwrap();
        let mut connection = OutboundConnection::handshake(
            io,
            Cipher::from_name(&suite, key.trim().as_bytes()).unwrap(),
            OutboundConfig {
                server_host: host,
                server_port: port,
                tls: false,
            },
        )
        .await
        .unwrap();
        let mut tasks = Vec::new();
        for id in 0..4 {
            let mut stream = connection
                .open_proxy_stream("127.0.0.1", target_port)
                .await
                .unwrap();
            tasks.push(tokio::spawn(async move {
                // open_proxy_stream already validates the encrypted CONNECT
                // response; waiting for another ACK would stall the echo test.
                let mut total = 0;
                for round in 0..rounds {
                    let plain: Vec<u8> = (0..payload)
                        .map(|i| ((i + round * 7 + id * 13) % 251) as u8)
                        .collect();
                    stream.send_payload(&plain).unwrap();
                    let mut received = Vec::new();
                    while received.len() < plain.len() {
                        for message in stream.recv_messages().await.unwrap().expect("DATA EOF") {
                            if message.type_ == MessageType::Data {
                                received.extend_from_slice(&message.data);
                            }
                        }
                    }
                    assert!(received == plain, "stream={id}, round={round}, received={}, expected={}, first_difference={:?}",
                        received.len(), plain.len(), received.iter().zip(&plain).position(|(a,b)| a != b));
                    total += received.len();
                }
                stream.send_disconnect().unwrap();
                total
            }));
        }
        let mut total = 0;
        for task in tasks {
            total += task.await.unwrap();
        }
        eprintln!(
            "CRYPTO_INTEROP_OK suite={suite} streams=4 bytes={total} byte_comparison=matched"
        );
    })
    .await
    .expect("end-to-end timeout");
}
