package com.proxy.subscription;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class ConsoleController {
    private final ConsoleService ops;
    private final AccountController access;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    ConsoleController(ConsoleService ops,AccountController access){this.ops=ops;this.access=access;}

    @ModelAttribute("actor") String authorize(){return access.requireAdmin();}
    private String view(Model m,String tab,String title){
        m.addAttribute("tab",tab); m.addAttribute("title",title);
        m.addAttribute("now",Instant.now().getEpochSecond());m.addAttribute("fmt",this);
        return "console";
    }
    public String date(Number seconds){return seconds==null?"—":TIME.format(Instant.ofEpochSecond(seconds.longValue()));}
    private String saved(RedirectAttributes flash,String location,String message){
        flash.addFlashAttribute("notice",message);return "redirect:/admin/"+location;
    }
    @GetMapping({"","/","/grants"}) String home(Model m){
        m.addAttribute("groupCount",ops.count("SELECT COUNT(*) FROM sp_group"));
        m.addAttribute("nodeCount",ops.count("SELECT COUNT(*) FROM sp_node WHERE enabled=true"));
        m.addAttribute("userCount",ops.userCount());
        m.addAttribute("activeCount",ops.count("SELECT COUNT(*) FROM subscription_account WHERE valid_until>? AND device_limit>0",Instant.now().getEpochSecond()));
        m.addAttribute("nodes",ops.nodes());m.addAttribute("groups",ops.groups());
        m.addAttribute("recent",ops.history(5,0).stream().limit(5).toList());
        return view(m,"home","运营概览");
    }
    private static int page(int page){return Math.max(0,Math.min(1000000,page));}
    @GetMapping("/users") String users(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,Model m){
        page=page(page);q=q.strip();if(q.length()>255)throw new ConsoleService.Problem("搜索内容过长。");
        var rows=ops.users(q,page);
        m.addAttribute("users",rows.stream().limit(30).toList());m.addAttribute("more",rows.size()>30);m.addAttribute("page",page);
        m.addAttribute("q",q);m.addAttribute("groups",ops.groups());return view(m,"users","用户管理");
    }
    @GetMapping("/users/{subject}") String user(@PathVariable String subject,Model m){
        m.addAttribute("u",ops.user(subject));m.addAttribute("groups",ops.groups());m.addAttribute("devices",ops.devices(subject));
        return view(m,"user","用户详情");
    }
    @PostMapping("/users") String create(@ModelAttribute("actor") String actor,@RequestParam String username,@RequestParam String display,
            @RequestParam String password,@RequestParam(defaultValue="") String groupId,@RequestParam(defaultValue="30") int days,
            @RequestParam(defaultValue="2") int deviceLimit,RedirectAttributes f){
        String sub=ops.createUser(actor,username,display,password,groupId,days,deviceLimit);
        return saved(f,"users/"+sub,"已创建登录账号。");
    }
    @PostMapping("/grants") String grant(@ModelAttribute("actor") String actor,@RequestParam String subject,@RequestParam String groupId,
            @RequestParam int days,@RequestParam int deviceLimit,RedirectAttributes f){
        ops.grant(actor,subject,groupId,days,deviceLimit);return saved(f,"users/"+safeSubject(subject),"订阅授权已保存。");
    }
    private String safeSubject(String subject){return org.springframework.web.util.UriUtils.encodePathSegment(subject,java.nio.charset.StandardCharsets.UTF_8);}
    @PostMapping("/users/{subject}/password") String password(@ModelAttribute("actor") String actor,@PathVariable String subject,@RequestParam String password,RedirectAttributes f){
        ops.password(actor,subject,password);return saved(f,"users/"+safeSubject(subject),"已更新登录密码。");
    }
    @PostMapping("/users/{subject}/devices/{device}/unbind") String unbind(@ModelAttribute("actor") String actor,@PathVariable String subject,@PathVariable String device,RedirectAttributes f){
        ops.unbind(actor,subject,device);return saved(f,"users/"+safeSubject(subject),"设备已解绑。");
    }
    @GetMapping("/groups") String groups(Model m){m.addAttribute("groups",ops.groups());return view(m,"groups","节点分组");}
    @PostMapping("/groups") String createGroup(@ModelAttribute("actor") String actor,@RequestParam String id,@RequestParam String label,RedirectAttributes f){
        ops.group(actor,id,label,true);return saved(f,"groups/"+id,"已创建分组，现在可以分配节点。");
    }
    @GetMapping("/groups/{id}") String group(@PathVariable String id,Model m){
        m.addAttribute("g",ops.one("SELECT group_id,label FROM sp_group WHERE group_id=?",id));m.addAttribute("nodes",ops.nodes());
        m.addAttribute("members",ops.groupMembers(id));
        return view(m,"group","分组详情");
    }
    @PostMapping("/groups/{id}/name") String rename(@ModelAttribute("actor") String actor,@PathVariable String id,@RequestParam String label,RedirectAttributes f){
        ops.group(actor,id,label,false);return saved(f,"groups/"+id,"分组名称已更新。");
    }
    @PostMapping("/groups/{id}/members") String members(@ModelAttribute("actor") String actor,@PathVariable String id,
                @RequestParam(name="nodeId",required=false) List<String> nodes,RedirectAttributes f){
        ops.membership(actor,id,nodes==null?List.of():nodes);return saved(f,"groups/"+id,"组内节点已保存，下次同步生效。");
    }
    @PostMapping("/groups/{id}/delete") String deleteGroup(@ModelAttribute("actor") String actor,@PathVariable String id,RedirectAttributes f){
        ops.removeGroup(actor,id);return saved(f,"groups","已删除空分组。");
    }
    @GetMapping("/nodes") String nodes(Model m){m.addAttribute("nodes",ops.nodes());return view(m,"nodes","节点管理");}
    @GetMapping("/nodes/new") String newNode(Model m){m.addAttribute("newNode",true);return view(m,"node","添加节点");}
    @GetMapping("/nodes/{id}") String node(@PathVariable String id,Model m){
        m.addAttribute("newNode",false);m.addAttribute("n",ops.one("SELECT node_id,label,host,port,server_name,enabled FROM sp_node WHERE node_id=?",id));
        m.addAttribute("groups",ops.nodeGroups(id));
        return view(m,"node","节点详情");
    }
    @PostMapping({"/nodes","/nodes/{existing}/edit"}) String saveNode(@ModelAttribute("actor") String actor,@PathVariable(required=false) String existing,
            @RequestParam(defaultValue="") String id,@RequestParam String label,@RequestParam String host,@RequestParam int port,
            @RequestParam String serverName,@RequestParam(defaultValue="") String ca,RedirectAttributes f) throws Exception {
        String ref=existing==null?id:existing;ops.saveNode(actor,ref,label,host,port,serverName,ca,existing==null);
        return saved(f,"nodes/"+ref,existing==null?"已保存草稿。请下载服务端接入包，完成配置后上线。":"已保存节点。");
    }
    @PostMapping("/nodes/{id}/state") String state(@ModelAttribute("actor") String actor,@PathVariable String id,@RequestParam String action,RedirectAttributes f){
        ops.nodeAction(actor,id,action);return saved(f,"delete".equals(action)?"nodes":"nodes/"+id,"节点状态已更新。");
    }
    @PostMapping("/nodes/{id}/bundle") ResponseEntity<byte[]> bundle(@ModelAttribute("actor") String actor,@PathVariable String id) throws Exception{
        ConsoleService.id(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Disposition","attachment; filename=\""+id+"-server.zip\"")
                .contentType(MediaType.parseMediaType("application/zip")).body(ops.bundle(actor,id));
    }
    @GetMapping("/runtime.jar") ResponseEntity<org.springframework.core.io.Resource> runtime(){
        var file=new org.springframework.core.io.FileSystemResource(ConsoleService.RUNTIME);
        if(!file.isReadable())throw new ConsoleService.Problem("当前部署尚未提供节点程序。");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"simpleplane-node.jar\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(file);
    }
    @GetMapping("/audit") String audit(@RequestParam(defaultValue="0") int page,Model m){
        page=page(page);var rows=ops.history(40,page);m.addAttribute("recent",rows.stream().limit(40).toList());m.addAttribute("page",page);m.addAttribute("more",rows.size()>40);
        return view(m,"audit","操作记录");
    }
    @ExceptionHandler({ConsoleService.Problem.class,DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(Exception failure,Model m){
        m.addAttribute("message",failure instanceof ConsoleService.Problem?failure.getMessage():"该标识或登录名已被使用，或记录仍有关联。");
        return view(m,"error","操作未保存");
    }
}
