package com.demo.bankagent.api;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.service.AccountService;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.DemoResetService;
import com.demo.bankagent.service.ErrorCode;
import com.demo.bankagent.service.Views.AccountSummaryView;
import com.demo.bankagent.service.Views.BeneficiaryView;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo-only endpoints: read the fictional profile and reset the local demo data (loopback only). */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoResetService resetService;
    private final AccountService accountService;
    private final BankAgentProperties properties;

    public DemoController(DemoResetService resetService, AccountService accountService,
                          BankAgentProperties properties) {
        this.resetService = resetService;
        this.accountService = accountService;
        this.properties = properties;
    }

    public record DemoProfile(AccountSummaryView summary, List<BeneficiaryView> beneficiaries) {
    }

    @GetMapping("/profile")
    public DemoProfile profile() {
        String customerId = properties.demo().customerId();
        return new DemoProfile(accountService.getSummary(customerId), accountService.listBeneficiaries(customerId));
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            log.warn("Rejected demo reset from non-loopback address");
            throw new BankingException(ErrorCode.LOCAL_ACCESS_ONLY, "Demo reset is only allowed from this machine (127.0.0.1).");
        }
        resetService.reset();
        log.info("Demo data reset completed");
        return Map.of("status", "RESET", "message", "Demo data restored and all conversations cleared.",
                "timestamp", Instant.now().toString());
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
