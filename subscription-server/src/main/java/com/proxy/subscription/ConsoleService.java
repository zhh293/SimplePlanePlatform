package com.proxy.subscription;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsoleService {
    static class Problem extends RuntimeException { Problem(String message) { super(message); } }
    final JdbcTemplate db;
    final EnrollmentService enrollment;
    final NodeMaterial vault;
    final String issuer;
    private final String identitySchema;
    private final Argon2PasswordEncoder passwords=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    ConsoleService(JdbcTemplate db, EnrollmentService enrollment, NodeMaterial vault,
                   @Value("${sso.client.issuer}") String issuer,
                   @Value("${subscription.identity-schema}") String identitySchema) {
        if (!identitySchema.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid identity schema name");
        this.db=db; this.enrollment=enrollment; this.vault=vault; this.issuer=issuer;
        this.identitySchema=identitySchema;
    }

    private String directory() {
        return identitySchema+".sp_subscription_directory";
    }

    long userCount() {
        return count("SELECT COUNT(*) FROM "+directory());
    }

    static String text(String value, int max, String field) {
        if(value==null || value.isBlank() || value.strip().length()>max || value.chars().anyMatch(Character::isISOControl))
            throw new Problem("请检查"+field+"。");
        return value.strip();
    }
    static String id(String value) {
        if(value==null || !value.matches("[a-z0-9][a-z0-9_-]{0,95}")) throw new Problem("标识应为小写字母、数字、短横线或下划线。");
        return value;
    }
    static String address(String input) {
        String host=text(input,253,"主机地址");
        if (!host.matches("[A-Za-z0-9][A-Za-z0-9.:-]*") && !host.matches(":[0-9A-Fa-f:]+"))
            throw new Problem("主机地址只填写 IP 或主机名。");
        return host;
    }

    void audit(String actor,String action,String target) {
        db.update("INSERT INTO sp_audit(happened_at,actor,action_name,target) VALUES(?,?,?,?)",Instant.now().getEpochSecond(),actor,action,target);
    }
    long count(String sql,Object... args) {return db.queryForObject(sql,Long.class,args);}
    Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new Problem("记录不存在，可能已被其他管理员移除。");
        return rows.get(0);
    }
    List<Map<String,Object>> groups() {
        return db.queryForList("""
            SELECT g.group_id,g.label,(SELECT COUNT(*) FROM sp_group_node m WHERE m.group_id=g.group_id) AS nodes,
            (SELECT COUNT(*) FROM subscription_account a WHERE a.group_id=g.group_id) AS users
            FROM sp_group g ORDER BY g.group_id
            """);
    }
    List<Map<String,Object>> nodes() {
        return db.queryForList("""
            SELECT n.node_id,n.label,n.host,n.port,n.server_name,n.configured,n.enabled,
            (SELECT GROUP_CONCAT(g.label ORDER BY g.group_id SEPARATOR '、') FROM sp_group_node m JOIN sp_group g ON g.group_id=m.group_id
             WHERE m.node_id=n.node_id) AS group_labels FROM sp_node n ORDER BY n.node_id
            """);
    }
    List<EnrollmentService.Device> devices(String subject) { return enrollment.devices(subject); }
    List<String> groupMembers(String group) {
        return db.queryForList("SELECT node_id FROM sp_group_node WHERE group_id=?",String.class,group);
    }
    List<Map<String,Object>> nodeGroups(String node) {
        return db.queryForList("SELECT g.group_id,g.label FROM sp_group g JOIN sp_group_node m ON g.group_id=m.group_id WHERE m.node_id=? ORDER BY g.group_id",node);
    }
    private String usersSql() {
        return " FROM "+directory()+" u LEFT JOIN subscription_account a ON a.account_id=UNHEX(SHA2(CONCAT(?,CHAR(0),u.subject_id),256)) "
                +"LEFT JOIN sp_group g ON g.group_id=a.group_id ";
    }
    private static final String USER_COLUMNS="""
            SELECT u.subject_id,u.username,u.display_name,u.enabled,a.group_id,g.label AS group_label,a.valid_until,a.device_limit,
            (SELECT COUNT(*) FROM subscription_device d WHERE d.account_id=a.account_id) AS devices,
            EXISTS(SELECT 1 FROM sp_console_user c WHERE c.subject_id=u.subject_id) AS managed
            """;
    List<Map<String,Object>> users(String search,int page) {
        return db.queryForList(USER_COLUMNS+usersSql()+" WHERE LOCATE(?,u.username)>0 OR LOCATE(?,COALESCE(u.display_name,''))>0 OR LOCATE(?,u.subject_id)>0 ORDER BY u.username LIMIT 31 OFFSET ?",
                issuer,search,search,search,30L*page);
    }
    Map<String,Object> user(String subject) {
        return one(USER_COLUMNS+usersSql()+"WHERE u.subject_id=?",issuer,subject);
    }
    List<Map<String,Object>> history(int limit,int page) {
        return db.queryForList("SELECT a.action_name,a.target,a.happened_at,COALESCE(u.username,a.actor) AS operator_name FROM sp_audit a LEFT JOIN "+directory()+
                " u ON u.subject_id=a.actor ORDER BY a.id DESC LIMIT ? OFFSET ?",limit+1,(long)limit*page);
    }
    @Transactional
    public void group(String actor,String ref,String label,boolean create) {
        label=text(label,100,"组名");
        if(create) db.update("INSERT INTO sp_group(group_id,label) VALUES(?,?)",id(ref),label);
        else {one("SELECT group_id FROM sp_group WHERE group_id=? FOR UPDATE",ref);db.update("UPDATE sp_group SET label=? WHERE group_id=?",label,ref);}
        audit(actor,create?"创建分组":"修改组名",ref);
    }
    @Transactional
    public void membership(String actor,String group,List<String> ids) {
        one("SELECT group_id FROM sp_group WHERE group_id=? FOR UPDATE",group);
        var selected=new TreeSet<>(ids);
        if (selected.size()>128 || !new HashSet<>(db.queryForList("SELECT node_id FROM sp_node",String.class)).containsAll(selected))
            throw new Problem("节点选择无效，每组最多 128 条线路。");
        db.update("DELETE FROM sp_group_node WHERE group_id=?",group);
        for(String n:selected) db.update("INSERT INTO sp_group_node(group_id,node_id) VALUES(?,?)",group,n);
        audit(actor,"分配组内节点",group+" · "+selected.size()+" 条");
    }
    @Transactional
    public void removeGroup(String actor,String group) {
        one("SELECT group_id FROM sp_group WHERE group_id=? FOR UPDATE",group);
        if(count("SELECT COUNT(*) FROM subscription_account WHERE group_id=?",group)>0 ||
                count("SELECT COUNT(*) FROM sp_group_node WHERE group_id=?",group)>0) throw new Problem("请先移走组内节点，并将用户授权转到其他组。");
        db.update("DELETE FROM sp_group WHERE group_id=?",group);audit(actor,"删除空分组",group);
    }
    @Transactional(rollbackFor=Exception.class)
    public void saveNode(String actor,String ref,String label,String host,int port,String serverName,String ca,boolean create) throws Exception {
        label=text(label,100,"节点名称"); host=address(host); serverName=address(serverName);
        if(port<1 || port>65535)throw new Problem("端口必须在 1 到 65535 之间。");
        if(!create) one("SELECT node_id FROM sp_node WHERE node_id=? FOR UPDATE",ref);
        if(!ca.isBlank()){
            if(ca.length()>16000 || ca.contains("PRIVATE KEY") || !ca.strip().matches("(?s)-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----"))
                throw new Problem("请粘贴一张 PEM 格式的公开 CA 证书。");
            try {
                X509Certificate cert=NodeMaterial.certificate(ca.getBytes(StandardCharsets.US_ASCII));
                cert.checkValidity();ca=NodeCatalog.pem(cert.getEncoded());
            } catch(java.security.GeneralSecurityException invalid){throw new Problem("证书格式无效或已过期。");}
        }
        if(create){
            id(ref);
            if("new".equals(ref))throw new Problem("请换一个节点标识，new 已用于添加节点页面。");
            db.update("INSERT INTO sp_node(node_id,label,host,port,server_name,enabled,configured,server_ca,sealed_material) VALUES(?,?,?,?,?,false,false,?,?)",
                    ref,label,host,port,serverName,ca.isBlank()?null:ca,vault.create(ref));
        } else {
            db.update("UPDATE sp_node SET label=?,host=?,port=?,server_name=? WHERE node_id=?",label,host,port,serverName,ref);
            if(!ca.isBlank())db.update("UPDATE sp_node SET server_ca=? WHERE node_id=?",ca,ref);
        }
        audit(actor,create?"添加节点":"修改节点",ref);
    }
    @Transactional
    public void nodeAction(String actor,String ref,String action) {
        var row=one("SELECT enabled,server_ca FROM sp_node WHERE node_id=? FOR UPDATE",ref);
        switch(action) {
            case "on" -> {
                if(row.get("server_ca")==null)throw new Problem("请先在节点服务器完成安装，并保存它的公开 CA 证书。");
                db.update("UPDATE sp_node SET enabled=true WHERE node_id=?",ref);
            }
            case "off" -> db.update("UPDATE sp_node SET enabled=false WHERE node_id=?",ref);
            case "delete" -> {
                if(((Boolean)row.get("enabled")))throw new Problem("请先下线节点。");
                db.update("DELETE FROM sp_node WHERE node_id=?",ref);
            }
            default -> throw new Problem("未知操作。");
        }
        audit(actor,Map.of("on","上线节点","off","下线节点","delete","删除节点").get(action),ref);
    }
    @Transactional(rollbackFor=Exception.class)
    public byte[] bundle(String actor,String ref) throws Exception {
        var node=one("SELECT node_id,port,server_name FROM sp_node WHERE node_id=?",ref);
        byte[] sealed=db.queryForObject("SELECT sealed_material FROM sp_node WHERE node_id=?",byte[].class,ref);
        var keys=vault.open(ref,sealed);
        var buf=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(buf)){
            zipFile(zip,"device-ca.crt",NodeCatalog.pem(keys.issuer().getEncoded()));
            zipFile(zip,"subscription-frame.key",keys.frame()+"\n");
            if(Files.isRegularFile(RUNTIME)){
                var digest=MessageDigest.getInstance("SHA-256");
                try(var file=Files.newInputStream(RUNTIME);var in=new java.security.DigestInputStream(file,digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}
                zipFile(zip,"node.json",new ObjectMapper().writeValueAsString(Map.of("id",ref,"port",node.get("port"),"serverName",node.get("server_name"),"jarSha256",HexFormat.of().formatHex(digest.digest()))));
                try(var script=new org.springframework.core.io.ClassPathResource("node-install.sh").getInputStream()){
                    zipFile(zip,"install.sh",new String(script.readAllBytes(),StandardCharsets.UTF_8));
                }
            }
            zipFile(zip,"接入说明.txt","新 Linux 服务器（Java 17+、systemd 249+、OpenSSL、Python 3、ss）：从详情页下载节点程序 simpleplane-node.jar，与解压后的 install.sh 放在同一目录。以 root 执行 sh install.sh；将输出的完整公开 CA 证书粘贴回节点详情，放行相应 UDP 端口，再上线并分配节点。\n\n"
                    +"沿用已有节点 "+ref+"：将 device-ca.crt 和 subscription-frame.key 放入仅节点服务账户可读的目录。\n"
                    +"对现有 SimplePlane HTTP/3、chacha20 服务使用支持共享监听器的版本，保留原来的服务器 TLS 证书和手动密钥。\n"
                    +"在 Java 启动参数（-cp 或 -jar 之前）加入以下两项，用实际绝对路径替换 /etc/simpleplane。\n"
                    +"-Dsimpleplane.deviceCaFile=/etc/simpleplane/device-ca.crt\n-Dsimpleplane.subscriptionFrameKeyFile=/etc/simpleplane/subscription-frame.key\n"
                    +"重启该节点并核实监听端口。在管理后台上线，并加入目标节点组。该接入包仅供节点运维方使用。\n");
        }
        audit(actor,"下载服务端接入包",ref);return buf.toByteArray();
    }
    static final Path RUNTIME=Path.of("/app/node-runtime.jar");
    private static void zipFile(ZipOutputStream zip,String name,String contents) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(contents.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    private String hash(String password){
        if(password==null || password.length()<12 || password.length()>1024)throw new Problem("密码长度应为 12 到 1024 个字符。");
        return passwords.encode(password);
    }
    @Transactional
    public String createUser(String actor,String username,String display,String password,String group,int days,int limit) {
        username=text(username,128,"登录名").toLowerCase(Locale.ROOT);
        if(!username.matches("[a-z0-9._@+-]+"))throw new Problem("登录名只能包含英文字母、数字及 . _ @ + -。");
        String subject=UUID.randomUUID().toString();
        db.update("CALL "+identitySchema+".sp_subscription_create(?,?,?,?,?)",subject,username,text(display,100,"显示名"),hash(password),actor);
        db.update("INSERT INTO sp_console_user(subject_id) VALUES(?)",subject);
        audit(actor,"创建用户",username);
        if(!group.isBlank()) grant(actor,subject,group,days,limit);
        return subject;
    }
    @Transactional
    public void password(String actor,String subject,String password) {
        if(count("SELECT COUNT(*) FROM sp_console_user WHERE subject_id=?",subject)!=1)throw new Problem("仅可重置在本后台创建的账号。");
        db.update("CALL "+identitySchema+".sp_subscription_password(?,?,?)",subject,hash(password),actor);
        audit(actor,"重置登录密码",(String)user(subject).get("username"));
    }
    @Transactional
    public void grant(String actor,String subject,String group,int days,int limit) {
        var u=user(subject);one("SELECT group_id FROM sp_group WHERE group_id=? FOR UPDATE",group);
        if(days<0 || days>3650 || limit<0 || limit>100)throw new Problem("有效天数须为 0–3650，设备名额须为 0–100。");
        if(days>0 && limit>0 && ((Number)u.get("devices")).intValue()>limit)throw new Problem("请先解绑多出的设备，再降低名额。");
        enrollment.grant(subject,new EnrollmentService.Grant(group,Instant.now().getEpochSecond()+days*86400L,limit));
        audit(actor,days==0 || limit==0?"停止订阅":"保存订阅授权",u.get("username")+" · "+group);
    }
    @Transactional
    public void unbind(String actor,String subject,String device) {
        enrollment.unbind(subject,device);audit(actor,"解绑设备",user(subject).get("username")+" · "+device);
    }
}
