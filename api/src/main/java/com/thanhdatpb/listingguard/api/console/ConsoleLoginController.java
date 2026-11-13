package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleLoginController {

    public record EmailForm(@NotBlank @Email @Size(max = 254) String email) { }

    private final org.springframework.beans.factory.ObjectProvider<MagicLinkService> magicLinks;
    private final ConsoleProperties properties;
    private final ConsoleMessages messages;
    private final HttpSessionSecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public ConsoleLoginController(org.springframework.beans.factory.ObjectProvider<MagicLinkService> magicLinks,
                                  ConsoleProperties properties, ConsoleMessages messages) {
        this.magicLinks = magicLinks;
        this.properties = properties;
        this.messages = messages;
    }

    @GetMapping("/login")
    public String login(Model model, @RequestParam(required = false) String logout,
                        @RequestParam(required = false) String access, HttpServletResponse response) {
        boolean denied = "denied".equals(access);
        if (denied) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            model.addAttribute("error", messages.get("login.error.accessdenied"));
        }
        if (properties.cloudflareAccessEnabled() && !properties.magicLinkEnabled()) {
            if (logout != null && !denied) model.addAttribute("message", messages.get("login.message.signedout.access"));
            model.addAttribute("accessLogin", true);
            model.addAttribute("accessAvailable", true);
            return "console/login";
        }
        model.addAttribute("accessAvailable", properties.cloudflareAccessEnabled());
        model.addAttribute("emailForm", new EmailForm(""));
        if (logout != null && !denied) model.addAttribute("message", messages.get("login.message.signedout"));
        return "console/login";
    }

    @PostMapping("/login")
    public String requestLink(@Valid @ModelAttribute("emailForm") EmailForm form, BindingResult binding,
                              HttpServletRequest request, Model model) {
        if (!properties.magicLinkEnabled()) return "redirect:/login";
        if (binding.hasErrors()) {
            model.addAttribute("error", messages.get("login.error.email"));
            return "console/login";
        }
        magicLinks.getObject().requestSignIn(form.email(), request.getRemoteAddr());
        model.addAttribute("message", messages.get("login.message.requested"));
        model.addAttribute("emailForm", new EmailForm(""));
        return "console/login";
    }

    @GetMapping("/login/verify")
    public String verifyPage(@RequestParam(required = false) String token, Model model) {
        MagicLinkService service = magicLinks.getIfAvailable();
        if (service == null || !service.isValid(token)) model.addAttribute("valid", false);
        else {
            model.addAttribute("valid", true);
            model.addAttribute("token", token);
        }
        return "console/verify";
    }

    @PostMapping("/login/verify")
    public String verify(@RequestParam(required = false) String token, HttpServletRequest request,
                         HttpServletResponse response, Model model) {
        MagicLinkService service = magicLinks.getIfAvailable();
        Optional<ConsoleUser> user = service == null ? Optional.empty() : service.consume(token);
        if (user.isEmpty()) {
            model.addAttribute("valid", false);
            return "console/verify";
        }
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.get(), null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSOLE"))));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return "redirect:/console";
    }
}
