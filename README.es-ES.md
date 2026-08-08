

# SimplePlanePlatform

Marco de proxy de túneles encriptados de alto rendimiento diseñado bajo el concepto de microkernel de Dubbo. Soporta los modos de proxy SOCKS5/HTTP CONNECT y el modo de proxy transparente global TUN, y proporciona un cliente para Android basado en el sistema `VpnService`. La cadena completa de SPI es enchufable, y la tolerancia a fallos en clúster, el balanceo de carga, los algoritmos de cifrado y las implementaciones de la capa de transporte pueden extenderse y reemplazarse de forma independiente.

## Posición del Proyecto

Aplica la arquitectura clásica de microkernel + plugins de Dubbo al escenario de proxies de red. Soporta tres formas de uso:

- **Modo Proxy (SOCKS5 / HTTP CONNECT)**：La aplicación dirigedirige activamente el tráfico a través del puerto del proxy, ideal para navegadores/entornos de línea de comandos, etc., donde se puede configurar un proxy.
- **Modo TUN (Proxy Transparente Global)**：Captura todo el tráfico del sistema a través de una interfaz de red virtual, combinado con FakeDNS para lograr un proxy global invisible para las aplicaciones, sin necesidad de configurarlo uno por uno.
- **Cliente Android**：Establece TUN basado en el sistema `VpnService`, llamando mediante JNI a la superficie de datos en Rust (`plane-core`) para completar FakeDNS, enrutamiento de dominios y túneles encriptados con ChaCha20, con total interoperabilidad de protocolo y cifrado con el servidor de escritorio (`proxy-remote`).

## Vista General de la Arquitectura

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         TUN Mode (tun-adapter)                          │
│   utun9 虚拟网卡 → smoltcp 用户态协议栈 → FakeDNS/真实DNS分流          │
│   → 域名路由判断 → SOCKS5 Client → proxy-local                         │
├─────────────────────────────────────────────────────────────────────────┤
│                         Local Server Layer                               │
│   ProxyLocalServer / ProtocolDetector / SOCKS5 / HTTP CONNECT           │
├─────────────────────────────────────────────────────────────────────────┤
│                         Cluster Layer                                    │
│   ClusterInvoker: Failover / Failfast / Forking / Failback              │
│   LoadBalance: RoundRobin / Random / LeastActive / ConsistentHash       │
├─────────────────────────────────────────────────────────────────────────┤
│                         Filter Chain Layer                               │
│   Router → RateLimit → Monitor → AccessLog → Traffic                    │
├─────────────────────────────────────────────────────────────────────────┤
│                         Exchange Layer                                   │
│   HeaderExchangeClient / DefaultFuture / RequestId-Future 映射          │
├─────────────────────────────────────────────────────────────────────────┤
│                         Transport Layer                                  │
│   NettyClient / HTTP/2 Stream 多路复用 / 连接管理                       │
├─────────────────────────────────────────────────────────────────────────┤
│                         Codec & Crypto Layer                             │
│   ProxyMessage 编解码 / AES-GCM / ChaCha20 / AES-CTR-HMAC              │
├─────────────────────────────────────────────────────────────────────────┤
│                         SPI Core (proxy-common)                          │
│   ExtensionLoader / @SPI / @Activate / @Order                           │
└─────────────────────────────────────────────────────────────────────────┘
```

### Flujo de Datos en Modo TUN

```
┌───────────┐   DNS query    ┌──────────────────────────────────────────────┐
│  任意 App  │ ────────────→ │            tun-adapter (utun9)               │
│           │   TCP/UDP      │                                              │
└───────────┘ ────────────→ │  ┌─────────────────────────────────────┐     │
                             │  │  DNS 分流引擎                        │     │
                             │  │  ├─ 内网域名 → 转发到真实 DNS 服务器 │     │
                             │  │  └─ 外网域名 → FakeDNS 分配虚拟 IP  │     │
                             │  └─────────────────────────────────────┘     │
                             │  ┌─────────────────────────────────────┐     │
                             │  │  路由判断                            │     │
                             │  │  ├─ 内网 IP/域名 → 直连（bypass）   │     │
                             │  │  └─ 外网 IP → SOCKS5 转发           │     │
                             │  └─────────────────────────────────────┘     │
                             └──────────────────┬───────────────────────────┘
                                                │ SOCKS5
                                                ▼
                             ┌──────────────────────────────────────────────┐
                             │         proxy-local (port 1080)              │
                             │    Filter Chain → Cluster → Exchange         │
                             └──────────────────┬───────────────────────────┘
                                                │ HTTP/2 加密隧道
                                                ▼
                             ┌──────────────────────────────────────────────┐
                             │     proxy-remote (远程服务器)                 │
                             │         → 代为访问目标网站                    │
                             └──────────────────────────────────────────────┘
```

### Principio de Coexistencia en la Red Interna

El desafío principal del modo TUN es no interrumpir las conexiones de red interna (como VPN o servicios empresariales). Este proyecto garantiza la accesibilidad de la red interna mediante tres mecanismos:

1. **Desvío de DNS**：Intercepta todas las solicitudes DNS en la pila de protocolos user-space de smoltcp, reenvía los dominios de red interna (como `*.sankuai.com`) al servidor DNS real de la red interna para obtener la IP real, y los dominios de internet usan FakeDNS para asignar IPs virtuales.
2. **Rutas de bypass**：Añade rutas de bypass en la tabla de enrutamiento del sistema para segmentos de red interna (10/8, 11/8, 172.16/12, 192.168/16) y la IP del servidor proxy, para que este tráfico no ingrese al dispositivo TUN.
3. **Desvío de resolver de macOS**：Crea archivos de configuración en `/etc/resolver/` para los dominios de red interna, permitiendo que las consultas DNS a nivel de sistema también se enrut correctamente al DNS de la red interna.

## Estructura de Módulos

```
SimplePlanePlatform/
├── proxy-common            SPI 内核：接口定义、ExtensionLoader、注解体系、数据模型
├── proxy-exchange          交换层：请求-响应语义（RequestId + Future 映射）
├── proxy-transport-netty   传输层：Netty HTTP/2 多路复用、编解码、心跳
├── proxy-crypto            加密层：4 种可插拔 AEAD 加密实现
├── proxy-cluster           集群层：4 种容错策略 + 4 种负载均衡 + 6 个 Filter
├── proxy-local             本地客户端：SOCKS5/HTTP CONNECT 双协议、路由分流、系统代理
├── proxy-remote            远程服务端：请求分发、出站连接管理、nginx 部署脚本
├── tun-adapter             TUN 透明代理：Rust 实现，smoltcp 协议栈 + FakeDNS + 域名路由
├── plane-core              Android 数据面：Rust JNI 库（libplane_core.so），用户态栈 + FakeDNS + 出站加密隧道
├── android-app             Android 客户端：Kotlin VpnService + JNI 桥接，gradle 构建 APK
├── scripts/build-rust.sh   cargo-ndk 交叉编译 plane-core 为各 ABI 的 .so 并写入 jniLibs
├── dashboard               Web 管理面板：可视化管理服务启停、配置编辑、日志查看
├── start-tun.sh            TUN 模式一键启动脚本 (macOS)
├── start-tun.ps1           TUN 模式一键启动脚本 (Windows PowerShell)
├── start-tun.bat           Windows 双击启动器（自动提权）
└── restore-dns.sh          TUN 异常退出后的 DNS 恢复脚本 (macOS)
```

## Inicio Rápido

### Requisitos del Entorno

| Componente | Requisito | Propósito |
|------|------|------|
| JDK | 1.8+ | Compilar y ejecutar proxy-local / proxy-remote |
| Maven | 3.6+ | Construcción de proyectos Java |
| Rust | stable（1.70+） | Compilar tun-adapter / plane-core |
| Node.js | 14+ | Ejecutar Dashboard Web（零外部依赖，无需 npm install） |
| macOS | 10.15+ | Modo TUN（requiere permisos root） |
| Windows | 10 1903+ | Modo TUN（requiere permisos de administrador, controlador WinTUN incluido en el crate tun2） |
| Servidor en la nube | IP pública | Desplegar proxy-remote |
| Android | 7.0+（API 24） | Cliente Android（la construcción requiere JDK 17 + Android SDK/NDK + cargo-ndk） |

### Compilar y Empaquetar

```bash
git clone https://github.com/zhh293/SimplePlanePlatform.git
cd SimplePlanePlatform

# Java 部分（proxy-local + proxy-remote）
mvn clean package -DskipTests

# Rust TUN 适配器
cd tun-adapter
cargo build --release
cd ..
```

Ubicación de los artefactos:

- `proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar`（本地代理客户端）
- `proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar`（远程代理服务端）
- `tun-adapter/target/release/tun-adapter`（TUN 透明代理）

## Desplegar el Servidor Remoto

Sube el jar al servidor:

```bash
scp -i <your-key.pem> proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar <user>@<server-ip>:~/
```

### Método 1: Despliegue con Proxy Inverso Nginx (Recomendado)

En entornos de producción se recomienda utilizar nginx para hacer proxy TCP de capacapa 4, y que Netty solo se vincule a `127.0.0.1` sin estar expuesto directamente:

```bash
ssh -i <your-key.pem> <user>@<server-ip> 'bash -s' < proxy-remote/deploy-nginx.sh
```

El script realiza automáticamente: instalar del módulo stream de nginx → escritura de configuración → inicio de Netty(127.0.0.1:19090) → nginx escucha en 9090 y reenvía. Arquitectura final:

```
客户端 → nginx(0.0.0.0:9090) → Netty(127.0.0.1:19090) → 目标网站
```

### Método 2: Despliegue Directo

Si no necesitas la capa de nginx, puedes iniciar directamente（necesitas cambiar el `host` en `remote.yml` a `0.0.0.0` y el `port` a `9090`）：

```bash
nohup java -jar proxy-remote-1.0.0-SNAPSHOT.jar > proxy-remote.log 2>&1 &
```

Asegúrate de que el grupo de seguridad / firewall tenga habilitado el puerto correspondiente（TCP entrada）.

## Modo de Uso 1: Modo Proxy (SOCKS5 / HTTP CONNECT)

Ideal para navegadores o aplicaciones que admiten configuraciones de proxy.

### Configuración

Edita `proxy-local/src/main/resources/proxy.yml`：

```yaml
localPort: 1080
remoteServers:
  - host: "YOUR_SERVER_IP"
    port: 9090
    ssl: false
    cipher: "none"
    cipherKey: "your-cipher-key"
cluster: failover
loadBalance: roundrobin
timeoutMs: 30000
connectionsPerNode: 1
httpProxyEnabled: true

route:
  defaultRoute: direct
  proxyList:
    - "*.google.com"
    - "*.github.com"
    - "*.youtube.com"
    - "*.openai.com"
  directList:
    - "*.baidu.com"
    - 127.0.0.1
    - localhost
```

Prioridad de ruta：`directList` > `proxyList` > `defaultRoute`.

### Iniciar

```bash
mvn package -pl proxy-local -am -DskipTests

# 启动系统代理（在 proxy-local 目录下执行）
java -jar ./target/proxy-local-1.0.0-SNAPSHOT.jar
```

Si ves `Proxy Local Server started on port 1080`, significa que se inició correctamente.

### Verificación

```bash
curl -x socks5://127.0.0.1:1080 --max-time 10 -I https://www.google.com
curl -x http://127.0.0.1:1080 --max-time 10 -I https://github.com
```

### Configuración del Proxy del Sistema

Se puede habilitar la configuración automática en `proxy.yml`：

```yaml
systemProxy:
  enabled: true
  host: 127.0.0.1
```

O configura manualmente el proxy del sistema para que apunte a `127.0.0.1:1080`（HTTP/SOCKS5 son compatibles）, o utiliza complementos de navegador como SwitchyOmega.

## Modo de Uso 2: Modo Proxy Transparente Global TUN (Recomendado)

El modo TUN captura todo el tráfico del sistema mediante una interfaz de red virtual, permitiendo un proxy global sin necesidad de configurar cada aplicación. Es ideal para escenarios donde todo el tráfico debe pasar por el proxy, manteniendo simultáneamente el acceso normal a la red interna/VPN.

### Configuración Preliminar

1. **Edita `proxy-local/src/main/resources/proxy.yml`**：Configura la dirección del servidor remoto, establece `defaultRoute` en `proxy`（en el modo TUN, todo el tráfico que entra en proxy-local debe pasar por el proxy）.

2. **Edita `tun-adapter/config/tun.toml`**：

```toml
[bypass]
# 【必填】代理服务器真实 IP，必须与 proxy.yml 中 remoteServers[].host 完全一致
proxy_remote_ips = ["YOUR_PROXY_REMOTE_IP"]
# 不进入 TUN 的内网网段
extra_cidrs = ["10.0.0.0/8", "11.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]

[intranet_dns]
# 内网 DNS 服务器地址
servers = ["11.11.11.11", "11.11.11.12"]
# 需要走内网 DNS 的域名后缀
domains = ["sankuai.com", "meituan.com", "sankuai.info", "neixin.cn", "dianping.com", "meituan.net"]
```

> ⚠️ **Importante: `proxy_remote_ips` debe contener la IP real del servidor `proxy-remote`, y debe coincidir exactamente con el `host` en `proxy.yml`.**
> El modo TUN utilizará las rutas `0.0.0.0/1` + `128.0.0.0/1` para capturar todo el segmento IPv4. Si no se excluye（bypass） la IP de `proxy-remote`, el tráfico de `proxy-local` hacia `proxy-remote` será redirigido otra vez a `tun-adapter` por `utun9`, creando un **bucle de enrutamiento**, lo que se manifiesta como "TUN se inicia correctamente pero no hay acceso a internet". Esta es la causa más común de fallo en este modo.

### Inicio con un Solo Comando

**macOS：**

> Antes de iniciar el modo TUN, cierra el proxy del sistema（no abras simultáneamente el modo Proxy y el modo TUN）, y luego ejecuta el script.

```bash
# 0. 进入项目目录
cd /Users/zhanghonghao/Desktop/SimplePlanePlatform

# 1.（强烈建议）先记录当前原始 DNS，作为人工对照基线
networksetup -getdnsservers Wi-Fi

# 2. 一键启动 TUN 模式（脚本已封装：启动 proxy-local → 启动 tun-adapter → 就绪检测）
sudo -v          # 先缓存 sudo 凭证，避免后台进程卡在密码输入
./start-tun.sh

# 3. 停止：在运行 start-tun.sh 的那个终端按 Ctrl+C
#    脚本会自动关掉两个进程并恢复 DNS/路由
```

El script automatiza los siguientes pasos:

1. Verifica si hubo una salida anómala anterior y, si es así, restaura primero DNS
2. Compila proxy-local（si es necesario） e inicia, espera a que el puerto 1080 esté listo
3. Compila tun-adapter（si es necesario） e inicia con sudo
4. Crea el dispositivo virtual utun9, configura las rutas del sistema y establece el desvío de DNS
5. Muestra la información del estado de ejecución

Una vez iniciado correctamente, todo el tráfico se desvía automáticamente sin necesidad de configuración manual. Presiona `Ctrl+C` para detenerlo, y el script restaurará automáticamente toda la configuración del sistema.

**Windows：**

```powershell
# 方式一：双击 start-tun.bat（会自动请求管理员权限）

# 方式二：以管理员身份打开 PowerShell
.\start-tun.ps1
```

El script de Windows automatiza：compilar tun-adapter → compilar proxy-local → iniciar Dashboard. El modo TUN utiliza el controlador WinTUN（incluido en el crate tun2, no requiere instalación adicional）, estableciendo rutas mediante `route add` y desviación de DNS mediante NRPT（Name Resolution Policy Table）.

### Inicio Manual（Avanzado）

Si necesitas depurar paso a paso:

**macOS：**

```bash
# 终端 1：启动 proxy-local
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar

# 终端 2：启动 tun-adapter（需要 root 权限）
cd tun-adapter
sudo ./target/release/tun-adapter -c config/tun.toml
```

**Windows（PowerShell como administrador）：**：**：**：

```powershell
# 终端 1：启动 proxy-local
java -jar proxy-local\target\proxy-local-1.0.0-SNAPSHOT.jar

# 终端 2：启动 tun-adapter（管理员权限）
cd tun-adapter
.\target\release\tun-adapter.exe -c config/tun.toml
```

### Verificación del Modo TUN

**macOS：**

```bash
# 检查 TUN 设备是否创建
ifconfig utun9

# 验证内网域名解析走真实 DNS（应返回 10.x.x.x 内网 IP）
nslookup your-intranet-domain.com

# 验证外网走代理
curl --max-time 10 -I https://www.google.com
```

**Windows（PowerShell como administrador）：

```powershell
# 检查 TUN 设备是否创建
Get-NetAdapter | Where-Object { $_.Name -like "*SimplePlane*" }

# 验证路由
route print | Select-String "198.18"

# 验证外网走代理
curl.exe --max-time 10 -I https://www.google.com
```

### Recuperación ante Errores

**macOS**：Si `tun-adapter` sale anómalamente（kill -9, cierre inesperado de la terminal, etc.）y causa problemas de red：

```bash
sudo ./restore-dns.sh
```

Este script lee el archivo de copia `/tmp/tun-adapter-dns-backup.conf`, restaura automáticamente la configuración de DNS, elimina la configuración en `/etc/resolver/` y actualiza la caché de DNS. Si la DNS original era "obtener automáticamente", se restaurará a automáticoautomática（macOS utiliza `networksetup -setdnsservers <service> Empty`, observaten en cuenta que `Empty` es un valor válido en macOS, `DHCP` no lo es）.

Si se ha perdido el archivo de respaldo o el script no puede restaurar, puedes reinicializar manualmente la DNS a automática：

```bash
sudo networksetup -setdnsservers Wi-Fi Empty   # 将 Wi-Fi 换成你的网络服务名
sudo rm -f /tmp/tun-adapter-dns-backup.conf
sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder
```

**Windows**：Al salir, `tun-adapter` limpiará automáticamente las reglas y las reglas NRPT. Si aún hay problemas de red, puedes restaurar manualmente en PowerShell como administrador：

```powershell
# 清除 NRPT 规则
Get-DnsClientNrptRule | Where-Object { $_.Comment -like "*tun-adapter*" } | Remove-DnsClientNrptRule -Force

# 恢复 DNS（从备份文件恢复，或设为自动获取）
Set-DnsClientServerAddress -InterfaceAlias "Wi-Fi" -ResetServerAddresses

# 清理残留路由
route delete 198.18.0.0
```

## Modo de Uso 3: Cliente Android

> ⚠️ **No disponible aún**：El cliente Android aún está en depuración interna. En esta fase, no se recomienda su uso; utiliza el modo Proxy o el modo TUN mencionados arriba. El siguiente contenido es solo un registro de desarrollo.

El cliente Android lleva todo el sistema de túneles encriptados al dispositivo móvil móvil：se basa en `VpnService` del sistema para crear TUN, todo el tráfico de la aplicación entra en la pila user-space a través de la interf virtual, y la superficie de datos en Rust（`plane-core`, compilada como `libplane_core.so`） completa la resolución de FakeDNS, la tomaevaluevalu判断 de enrutamiento de dominios y el túnel encriptado con ChaCha20, que luego se reenvía a `proxy-remote` a través del túnel HTTP/2. Comparte el mismo protocolo `ProxyMessage` y formato de cifrado ChaCha20-Poly1305 con el escritorio/de escritorio, logrando total interoperabilidad entre ambos extremos.

### Arquitectura

```
┌──────────────┐  全部流量   ┌──────────────────────────────────────────────┐
│  Android App  │ ─────────→ │     PlaneVpnService (Kotlin, VpnService)     │
│  (任意应用)   │   TUN fd   │  establish() → detachFd() 移交 native        │
└──────────────┘            └───────────────────┬──────────────────────────┘
                                                 │ JNI (NativeBridge)
                                                 ▼
                            ┌──────────────────────────────────────────────┐
                            │       plane-core (Rust, libplane_core.so)    │
                            │  用户态栈 → FakeDNS → 域名路由 → ChaCha20     │
                            │  回调 protect(fd) 把出站 socket 排除出 TUN    │
                            └───────────────────┬──────────────────────────┘
                                                 │ HTTP/2 加密隧道
                                                 ▼
                            ┌──────────────────────────────────────────────┐
                            │            proxy-remote (远程服务器)          │
                            └──────────────────────────────────────────────┘
```

Del lado de Kotlin, `PlaneVpnService` gestiona la solicitud de permisos de VPN, configura TUN（dirección/rutas/DNS/MTU）y transfiere el fd a native; `NativeBridge` es el puente JNI, que llama hacia arriba `nativeStart` / `nativeStop`, y recibe hacia abajo las devoluciones de llamada `protect`（anti-bucle, excluye sockets salientes del TUN）y `onStatus`（información de estado）de Rust.

### Entorno de Construcción

| Componente | Requisito |
|------|------|
| JDK | 17（Requisito de Gradle / AGP, ten en cuenta la diferencia con JDK 8 del servidor） |
| Android SDK | API 34（compileSdk） |
| Android NDK | r26+（cargo-ndk requiere para compilación cruzada） |
| Rust target | `aarch64-linux-android` / `armv7-linux-androideabi` / `x86_64-linux-android` |
| cargo-ndk | `cargo install cargo-ndk` |

Preparación inicial del toolchain:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
# 并通过 ANDROID_NDK_HOME 或 sdkmanager 安装 NDK r26+
```

### Construcción Local del APK

```bash
# 进入 Android 工程，Gradle 的 preBuild 会自动调用 scripts/build-rust.sh
# 用 cargo-ndk 把 plane-core 交叉编译进 jniLibs，再打包进 APK。
cd android-app

# Debug APK
./gradlew assembleDebug

# Release APK（Rust 以 release profile 编译）
./gradlew assembleRelease -PrustRelease
```

Ubicación de los artefactos：

- `android-app/app/build/outputs/apk/debug/app-debug.apk`
- `android-app/app/build/outputs/apk/release/app-release-unsigned.apk`

Si ya ejecutaste manualmente `scripts/build-rust.sh`, puedes añadir `-PskipRustBuild=true` para omitir el paso de construcción Rust dentro de Gradle. También puedes compilar cruzada la superficie de datos por separado：

```bash
scripts/build-rust.sh release   # 产出各 ABI 的 libplane_core.so 到 jniLibs
```

### Firma y Empaquetado de Release

Por defecto, `assembleRelease` produce un APK **unsigned**（que no se puede instalar directamente）sin el material de firma. Una vez configurada la firma, producirproduirá automáticamente `app-release.apk` firmado e instalable.

**1. Generar keystore（una vez）：

```bash
keytool -genkeypair -v -keystore release.jks \
  -alias simpleplane -keyalg RSA -keysize 2048 -validity 10000
# 按提示设置 keystore 密码、key 密码与证书信息，妥善保管 release.jks（勿入库）
```

**2. Firma local del paquete**：Crea un archivo `keystore.properties` en el directorio raíz del repositorio（ya excluido por `.gitignore`, no se subirá）：

```properties
storeFile=release.jks
storePassword=你的keystore密码
keyAlias=simpleplane
keyPassword=你的key密码
```

Luego ejecuta normalmente `cd android-app && ./gradlew assembleRelease -PrustRelease`, el artefacto será `app-release.apk`. También puedes usar variables de entorno `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` en lugar de este archivo（las variables de entorno tienen prioridad）.

**3. Empaquetado firmado firma en CI**：En GitHub Repo Settings → Secrets and variables → Actions, configura los siguientes 4 secrets, el job `android-build` restaurará automáticamente el keystore y producirá el APK de release firmado：

| Secret | 说明 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | 输出 `base64 -i release.jks`（macOS）或 `base64 -w0 release.jks`（Linux）的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | 密码 |
| `ANDROID_KEY_ALIAS` | 别名（如 `simpleplane`） |
| `ANDROID_KEY_PASSWORD` | 密码 |

Si no se configuran estos secrets（como al hacer Fork o en ramas sin configurar）, CI solo producirá el APK Debug, el paso de firma se omitirá automáticamente sin fallar.

### Empaquetado Automático de CI

El repositorio ya configura `.github/workflows/android-core.yml`, que se activaadena automáticamente cuando cambian y se pushan rutas como `plane-core/**`, `android-app/**`, `scripts/build-rust.sh`：

- `rust-test`：`cargo fmt --check` + `cargo clippy -D warnings` + `cargo test`（control de calidad obligatorio）；el porcentaje de cobertura es informativo.
- `android-build`：cargo-ndk compila cruzadamente los `.so` de tres ABI → `assembleDebug` → sube el APK Debug como artifact（`simpleplane-debug-apk`, se retiene por 14 días）. Si el repositorio tiene configurados los secrets de firma（ver sección anterior）, también ejecutará `assembleRelease` y subirá el APK de release firmado（`simpleplane-release-apk`）.

Tras el push, descarga el APK en los Artifacts del job `android-build` correspondiente en GitHub Actions.

## Panel de Gestión Web（Dashboard）

> ⚠️ **No disponible aún**：El panel de gestión web aún está en depuración interna. En esta fase, no se recomienda su uso; inicia el modo Proxy o el modo TUN directamente a través de la línea de comandos anterior. El siguiente contenido es solo un registro de desarrollo.

Dashboard proporciona una interfaz visual para gestionar toda la plataforma de proxy, incluyendo inicio/parada de servicios con un clic, edición de configuraciones en tiempo real, visualización de registros de ejecución, etc. **Cero dependencias externas, no requiere npm install**.

### Requisitos del Entorno

- Node.js 14+（se recomienda 18+）
- Asegúrate de que Java y Cargo estén instalados（Dashboard invocá `mvn` y `cargo` para la compilación）
- macOS o Windows 10+（Soporte multiplataforma para modo TUN）

### Iniciar Dashboard

```bash
cd dashboard
node server.js
```

Si ves `✓ SimplePlane Dashboard running at http://localhost:3000`, significa que se inició correctamente. Abre [http://localhost:3000](http://localhost:3000) en el navegador para acceder al panel.

**Nota para macOS**：Dashboard debe iniciarse en una terminal real（como Terminal.app, iTerm2）, no en un entorno sandboxed restringido, de lo contrario las llamadas `sudo` para el modo TUN fallarán.

**Nota para Windows**：Dashboard debe iniciarse como administrador（clic derecho en CMD/PowerShell → Ejecutar como administrador）, de lo contrario el modo TUN no podrá crear la interfaz de red virtual y modificar la tabla de enrutamiento.

Se puede modificar el puerto de escucha mediante variables de entorno：

```bash
DASHBOARD_PORT=8080 node server.js
```

### Configuración de Permisos para el Modo TUN（Requerido para el primer uso）

El modo TUN requiere permisos root. Para permitir que Dashboard inicie/detenga `tun-adapter` sin contraseña, debes **ejecutar primero el script de configuración de permisos**：

```bash
cd dashboard
chmod +x setup-tun-permissions.sh
./setup-tun-permissions.sh
```

El script pedirá la contraseña de administrador de Mac una vez, y luego escribirá la regla de acceso sin contraseña en `/etc/sudoers.d/simpleplane-tun`. Una vez configurado, Dashboard podrá podráiniciará/detendrá TUN directamente.

**Nota**：Cada vez que recompiles `tun-adapter`（`cargo build --release`）, no es necesario volver a configurar si la ruta del binario no ha cambiado. Si mudaste el directorio del proyecto, necesitas volver a ejecutar este script.

### Resumen de Funciones del Dashboard

#### Panel de Control

La página principal muestra el estado de ejecución de todos los servicios, ofreciendo las siguientes operacionesoperaciones：

| Operación | 说明 |
|------|------|
| 启动/停止/重启 proxy-local | Gestióngestiona el servicio proxy local SOCKS5/HTTP |
| 启动/停止/重启 tun-adapter | 管理 TUN 全局透明代理 |
| 编译 | 一键编译 proxy-local（mvn）或 tun-adapter（cargo） |
| 一键代理模式 | 启动 proxy-local + 开启 macOS 系统代理 |
| 一键 TUN 模式 | 启动 proxy-local + 启动 tun-adapter |
| 全部停止 | 停止所有服务 + 关闭系统代理 |
| 系统代理开关 | 切换 macOS Wi-Fi 的 SOCKS/HTTP 代理设置 |

#### Configuración del Proxy（Edición Visual de proxy.yml）

No edites manualmente/ manualmente los archivos YAML, todos los parámetros de proxy-local se pueden modificar en la interfaz：

| 配置项 | 界面位置 | 说明 |
|--------|----------|------|
| 监听端口 | 代理配置 → 监听端口 | 共用 puerto SOCKS5/HTTP CONNECT, por defecto 1080 |
| 集群策略 | 代理配置 → 集群策略 | failover / failfast / forking / failback |
| 负载均衡 | 代理配置 → 负载均衡 | roundrobin / random / leastactive / consistenthash |
| 超时 | 代理配置 → 超时 (ms) | 毫秒 de timeout de petición/petición, por defecto 30000 |
| 每节点连接数 | 代理配置 → 每节点连接数 | 数 de conexiones multiplexadas HTTP/2, 1-2 es suficiente |
| HTTP CONNECT | 代理配置 → HTTP CONNECT 开关 | 是否同时支持 HTTP 代理协议 |
| 远程服务器 | 代理配置 → 远程服务器 | 可添加/删除/编辑多个服务器节点 |

Cada nodo del servidor remoto puede configurar: Host（dirección）, Port（puerto）, Cipher（algoritmo de cifrado）, Key（clave）, SSL（si se habilita TLS）.

Tras modificar, haz clic en el botón "Guardar configuraciónación" en la esquina inferior izquierda, o presiona `Ctrl+S` / `Cmd+S` para guardar rápidamente.

#### Configuración del Modo TUN（Editor de tun.toml）

Proporciona un editor de texto para `tun-adapter/config/tun.toml`, admitiendo edición directa de la configuración TOML. Puedes hacer clic en "Guardar y Reiniciar" para aplicar los cambios con un clic.

#### Reglas de Enrutamiento

Edición visual de reglas/reglas de desvío de dominios：

| 配置项 | 说明 |
|--------|------|
| 默认路由 | `direct`（conexión directa por defecto）o `proxy`（pasar/por defecto） |
| 代理列表 | 走远程代理的域名, una por línea, admite comodines `*` |
| 直连列表 | 强制直连的域名（prioridad más alta）, una por línea |

Prioridad de ruta：lista de conexión directa > lista de proxy > ruta por defecto.

#### Registros de Ejecución

Visualiza en tiempo real los registros de ejecución de proxy-local y tun-adapter, con soporte para：

- Cambiar entre ver registros de diferentes servicios
- Desplazamiento automático al final
- Limp/limpiar visualización con un clic

### Rutas de Archivos de Configuración del Dashboard

Dashboard lee y escribe directamente en los siguientes archivos：

| 文件 | 路径 | 说明 |
|------|------|------|
| proxy.yml | `proxy-local/src/main/resources/proxy.yml` | 主配置 proxy-local |
| remote.yml | `proxy-remote/src/main/resources/remote.yml` | 配置 servidor proxy-remote |
| tun.toml | `tun-adapter/config/tun.toml` | ación del adaptador TUN |

Las modificaciones de configuración en Dashboard se escriben directamente en los archivos correspondientes, después de modificar es necesario reiniciar el servicio correspondiente para que surtan efecto.

### Comunicación en Tiempo Real del Dashboard

Dashboard implementa la /push en tiempo real mediante SSE（Server-Sent Events）：

- Los cambios de estado de inicio/parada de servicios actualizarán automáticamente el panel
- Los nuevos registros se /pusharán en tiempo real a la página de registros
- Los cambios en la configuración notificarán al frontend para actualizar

### Configuraciones Predefinidas

Puedes guardar la configuración actual como una configuraciónpresets para cambiar rápidamente（como "Red de Empresa", "Red en Casa", etc.）. Los presets se guardan en el directorio `dashboard/presets/`, en formato YAML.

## Referencia de Configuración del Modo TUN（tun.toml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| tun.name | utun9 (macOS) / SimplePlane (Windows) | 名称 dispositivo TUN |
| tun.address | 198.18.0.1 | Dirección IP del dispositivo TUN |
| tun.netmask | 255.254.0.0 | Máscara de subred del dispositivo TUN |
| tun.mtu | 1500 | Tamaño MTU |
| tun.enabled | true | Si se habilita el dispositivo TUN |
| fakeip.range | 198.18.0.0/15 | Rango de asignación de IPs virtuales FakeDNS |
| fakeip.capacity | 65536 | Capacidad de la tabla de mapeo FakeDNS |
| proxy.socks5_addr | 127.0.0.1:1080 | Dirección del proxy SOCKS5 upstream |
| proxy.health_check_interval | 5 | Intervalo de verificación de salud（segundos） |
| proxy.health_failure_threshold | 3 | Cuántos fallos consecutivos indican判定an el proxy como no disponible |
| routing.default_action | proxy | Acción de ruta por defecto（proxy/direct） |
| routing.rules[] | — | Reglas de ruta de dominio/IP（ver abajo） |
| bypass.proxy_remote_ips | — | IP del servidor proxy（ruta de bypass, obligatorio configurar） |
| bypass.extra_cidrs | — | Segmentos de red de bypass adicionales |
| bypass.dns_bypass_ips | — | IP de bypass DNS（如 114.114.114.114） |
| intranet_dns.servers | — | 服务器 DNS de la red interna |
| intranet_dns.domains | — | Lista de sufijos de dominio/domin de la red interna |
| log.level | info | Nivel de log（soporta niveles de módulo como `info,tun_adapter::socks5=debug`） |
| log.format | pretty | Formato de log |

Los tipos de reglas de ruta admiten：`domain_suffix`（coincidencia de sufijo de dominio）, `domain_keyword`（coincidencia de palabra clave de dominio）, `ip_cidr`（coincidencia de segmento IP）.

Ejemplo de reglas de ruta：

```toml
[[routing.rules]]
type = "domain_suffix"
value = "google.com"
action = "proxy"

[[routing.rules]]
type = "domain_keyword"
value = "baidu"
action = "direct"

[[routing.rules]]
type = "ip_cidr"
value = "10.0.0.0/8"
action = "direct"
```

## Referencia de Configuración

### proxy-local（proxy.yml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| localPort | 1080 | Puerto de escucha del proxy local |
| remoteServers[].host | — | 地址 servidor remoto |
| remoteServers[].port | 9090 | Puerto del servidor remoto |
| remoteServers[].cipher | none | Algoritmo de cifrado, debe coincidir con el servidor |
| remoteServers[].cipherKey | — | Clave de cifrado, debe coincidir con el servidor |
| remoteServers[].ssl | false | Si se habilita TLS |
| cluster | failover | Estrategia de tolerancia a fallos en clúster |
| loadBalance | roundrobin | Estrategia de balanceo de carga |
| timeoutMs | 30000 | Timeout de petición/petición（ms） |
| connectionsPerNode | 1 | 数 conexiones HTTP/2 por nodo |
| httpProxyEnabled | true | Si se admite/soporta HTTP CONNECT |
| route.defaultRoute | direct | Ruta por defecto（proxy/direct） |
| route.proxyList | [] | Lista de dominios para proxy（admite comodines） |
| route.directList | [] | Lista de dominios para conexión directa（admite comodines） |
| systemProxy.enabled | false | ación automática del proxy del sistema |

### proxy-remote（remote.yml）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| host | 127.0.0.1 | Dirección de escucha（modo proxy nginx se vincula al local） |
| port | 19090 | Puerto de escucha（nginx expone 9090 y reenvía a este puerto） |
| bizThreads | 200 | Tamaño del pool de hilos de negocios/negocios |
| cipher | none | Algoritmo de cifrado, debe coincidir con el cliente |
| cipherKey | — | Clave de cifrado, debe coincidir con el cliente |
| maxStreams | 1000 | ximo de Streams concurrentes por conexión |
| readIdleTimeout | 60 | Timeout de in ociosa（segundos） |
| outbound.connectTimeoutMs | 5000 | Timeout de conexión al sitio objetivo |
| outbound.activeWaitTimeoutMs | 5000 | Timeout de espera para que la conexión de salida esté lista |

## Despliegue con Docker

El proyecto proporciona una orquestación con docker-compose de un solo comando, ambos extremos se comunican dentro de la misma red compose：

```bash
docker compose up -d --build    # Construir y ejecutar
docker compose logs -f          # Ver logs
docker compose down             # Detener
```

En el modo contenedor, el puerto 1080 de `proxy-local` se mapea al host, y se conecta al servidor mediante el nombre de servicio `proxy-remote`. Para la configuración de cifrado, consulta `docker/proxy.yml` y `docker/remote.yml`.

## Habilitar Cifrado

El cliente y el servidor configuran el mismo `cipher` y `cipherKey`：

```yaml
# proxy.yml（客户端）
remoteServers:
  - host: "YOUR_SERVER_IP"
    port: 9090
    cipher: "aes-gcm"
    cipherKey: "your-secret-key"

# remote.yml（服务端）
cipher: aes-gcm
cipherKey: your-secret-key
```

Algoritmos soportados：`none`（sin cifrado）, `aes-gcm`（recomendado x86, aceleración de hardware Intel AES-NI）, `chacha20`（recomendado ARM）, `aes-ctr-hmac`（combinación clásica）.

> **Nota sobre el enmarcado en la capa de transporte**：Al transmitirse los datos encriptados en el túnel HTTP/2, los límites de los frames DATA no están garantizados para corresponderse uno a uno con el lado remit/remisión（afectado por maxFrameSize, control de flujo, coalescencia de frames, etc.）. Bajo/ grandes flujos de datos, un bloque de cifrado individual puede ser dividido/ dividido/ dividido entre frames. Para ello, ambos extrem（emisor y receptor）escriben un prefijo de 4 bytes con el endian grande de la longitud antes de cada bloque de cifrado, y el lado receptor acumula bytes a nivel de cifrado según esto, recopil/ decodificando solo cuando se completa el bloque, evitando la interrupción de la conexión por fallos en la verificación de etiquetas/etiqueta de autenticación AEAD. Las implementaciones de Java（`CipherEncodeHandler`/`CipherDecodeHandler`）y Rust（`plane-core`）son completamente simétricas, el formato de cifrado del algoritmo `chacha20` es consistente entre lenguajes（bloqueado por los vectores de prueba `docs/design/crypto-vectors.json`）.

## Flujo de Uso Completo（Desde Cero）

El siguiente es un ejemplo completo de despliegue desde cero：

### 1. Desplegar el Servidor Remoto

```bash
# 在云服务器上
scp -i your-key.pem proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar user@your-server:~/
ssh -i your-key.pem user@your-server
nohup java -jar proxy-remote-1.0.0-SNAPSHOT.jar > proxy-remote.log 2>&1 &
```

### 2. Configurar el Cliente Local

Edita `proxy-local/src/main/resources/proxy.yml`, ingresa la IP del servidor remoto, configura la clave de cifrado.

### 3. Seleccionar el Modo de Uso

**Modo Proxy**（simple, ideal para navegadores）：
```bash
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar
# Luego configura el proxy del sistema o del navegador como 127.0.0.1:1080
```

**Modo TUN**（global, recomendado）：
```bash
# 方式一：Dashboard 面板操作（推荐）
cd dashboard && node server.js
# Abre http://localhost:3000 → Clic/haz clic en "Modo TUN con un clic"

# 方式二：命令行一键启动
./start-tun.sh
```

### 4. Verificación

```bash
curl --max-time 10 -I https://www.google.com
```

## Preguntas Frecuentes

**Error `Address already in use` al iniciar** — El puerto está ocupado, ejecuta `kill $(lsof -ti :1080)` y reintenta.

**Error `Connection refused` al conectar con el remoto** — Confirma que el extremo remoto está iniciado, nginx funciona correctamente, el grupo de seguridad tiene el puerto habierto, y la IP en `proxy.yml` es correcta.

**Modo TUN "Se inicia correctamente pero no hay acceso a internet"** — La causa más común es que `bypass.proxy_remote_ips` en `tun.toml` no está configurado o está mal（como dejar el marcador de posición `your-remote-server-ip`）. Debe ser igual al `host` remoto en `proxy.yml`. De lo contrario, el tráfico de `proxy-local` hacia `proxy-remote` será redirigido otra vez a `utun9` por `tun-adapter`, creando un **bucle de enrutamiento**, lo que bloquea todo el tráfico/acceso. Corrixe y reinicia.

**Inaccesible en la red interna en modo TUN** — Confirma que `intranet_dns.domains` incluye todos los sufijos de dominios de la red interna, y `intranet_dns.servers` tiene la dirección DNS de la red interna correcta. También confirma que `bypass.extra_cidrs` cubre los segmentos de IP de la red interna.

**DNS roto tras salida anómala de TUN / error `DHCP is not a valid IP address` al recuperar** — macOS：ejecuta `sudo ./restore-dns.sh` para restaurar, o manualmente `sudo networksetup -setdnsservers Wi-Fi Empty && dscacheutil -flushcache`. Ten en cuenta que el valor válido para "obtener automáticamente" en macOS es `Empty`, no `DHCP`（versiones antigu escribían erróneamente `DHCP` en el archivo de respaldo, la nueva versión lo ha corregido y es retrocompatible）. Windows：ejecuta en PowerShell como administrador `Set-DnsClientServerAddress -InterfaceAlias "Wi-Fi" -ResetServerAddresses`.

**Dashboard reporta/ muestra EPERM al iniciar TUN** — macOS：Dashboard debe iniciarse desde una terminal real（no desde la terminal integrada de un IDE o entorno sandbox）, y confirma que ya ejecutaste `setup-tun-permissions.sh`. Windows：confirma que Dashboard se ejecuta como administrador.

**Dashboard se queda atascado en "Iniciando"**/** — Verifica si los logs de Dashboard muestran `sudo: a password is required`. Si es así, la configuración de sudoers no surtiÓ efecto, vuelve a ejecutar `setup-tun-permissions.sh`.

**Quiero diagnosticar la cadena de inicio de TUN de una vez** — En la terminal del sistema ejecuta `cd dashboard && ./diagnose-tun.sh`, verificará uno a uno los binarios, la configuración, el sudo sin contraseña, y pondrá a prueba si puede crear un dispositivo utun como root, proporcionando una conclusión clara al final.

**Timeout al acceder sin errores** — Verifica si el dominio está en `proxyList`（modo proxy）, o si está configurada una regla `direct` correspondiente en `routing.rules`（modo TUN）.

**Error en `cargo build`** — Asegúrate de que la toolchain de Rust está instalada：`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`

## Stack Tecnológico

| Tecnología | Versión | Propósito |
|------|------|------|
| Java | 1.8+ | Cliente/Servidor de proxy |
| Netty | 4.1.108 | Framework de red NIO, HTTP/2 |
| Rust | stable | Adaptador TUN / Superficie de datos Android（plane-core） |
| Kotlin | 1.9 | Cliente Android |
| Android Gradle Plugin | 8.x（compileSdk 34, minSdk 24） | Construcción de proyecto Android |
| cargo-ndk | — | Compila cruzadamente plane-core como .so para cada ABI |
| smoltcp | 0.11 | Pila de protocolos TCP/IP user-space |
| tokio | 1.x | Entorno asíncrono de Rust |
| tun2（crate） | 4.x | Operaciones de dispositivos/dispositivo TUN multiplataforma（macOS/Windows/Linux） |
| BouncyCastle | 1.70 | ChaCha20-Poly1305 |
| SnakeYAML | 2.2 |解析 de configuración YAML |
| SLF4J + Logback | 1.7.36 / 1.2.11 | Logging |
| Node.js | 14+ | Panel de gestión web |
| Nginx | 1.24+ | Proxy inverso TCP capa 4 |
| Docker | — | Despliegue en contenedores |

## Guía de Extensión

Gracias a la arquitectura SPI, extender cualquier capa solo requiere dos pasos：implementar la interfaz correspondiente → registrar en `META-INF/proxy/{nombre completo de la interfaz}`. No se requiere modificar el código existente del framework, cumpliendo con el principio abierto/cerrado.

Las reglas de enrutamiento del adaptador TUN también son extensibles; simplemente añade nuevas reglas en `[[routing.rules]]` de `tun.toml` para que surtan efecto.

## Licencia

MIT
