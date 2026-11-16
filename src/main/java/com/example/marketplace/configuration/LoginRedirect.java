package com.example.marketplace.configuration;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("!local")
public class LoginRedirect {
    @GetMapping("/login")
    String login() {
        return "redirect:/oauth2/authorization/marketplace";
    }
}
