package com.proxy.subscription;

import com.authsystem.sso.client.session.SsoCurrentUser;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class AccountController {
    private final EnrollmentService enrollment;
    private final Set<String> administrators;

    AccountController(EnrollmentService enrollment, @Value("${subscription.admin-subjects}") String subjects) {
        this.enrollment = enrollment;
        this.administrators = Arrays.stream(subjects.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    private static String subject() {
        return SsoCurrentUser.get().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).subject();
    }

    String requireAdmin() {
        String actor=subject();
        if (!administrators.contains(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }

    @GetMapping("/account/pair/{code}")
    String pair(@PathVariable String code, Model model) {
        model.addAttribute("pair", enrollment.view(code));
        return "pair";
    }

    @PostMapping("/account/pair/{code}")
    String decide(@PathVariable String code, @RequestParam String decision) {
        if (!Set.of("allow", "deny").contains(decision)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        enrollment.decide(subject(), code, decision.equals("allow"));
        return "redirect:/account/pair/" + code;
    }

    @GetMapping("/account/devices")
    String devices(Model model) {
        String owner = subject();
        model.addAttribute("devices", enrollment.devices(owner));
        model.addAttribute("subject", owner);
        model.addAttribute("administrator", administrators.contains(owner));
        return "devices";
    }

    @PostMapping("/account/devices/{id}/unbind")
    String unbind(@PathVariable String id) {
        enrollment.unbind(subject(), id);
        return "redirect:/account/devices";
    }

}
