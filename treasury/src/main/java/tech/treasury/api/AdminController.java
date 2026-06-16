package tech.treasury.api;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.treasury.agent.AgentAdminService;
import tech.treasury.domain.AgentEntity;
import tech.treasury.repo.AgentRepository;

import java.util.List;

/**
 * Admin CRUD for agents + policies, backing {@code /admin.html}.
 *
 * <p><b>UNAUTHENTICATED — local/demo only.</b> These endpoints create agents and mint API keys; do
 * not expose the app publicly with them enabled.
 */
@RestController
@RequestMapping("/api/admin/agents")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AgentAdminService admin;
    private final AgentRepository agents;

    public AdminController(AgentAdminService admin, AgentRepository agents) {
        this.admin = admin;
        this.agents = agents;
    }

    @GetMapping
    public List<AdminAgentView> list() {
        return agents.findAll().stream().map(AdminAgentView::of).toList();
    }

    @PostMapping
    public ResponseEntity<CreateAgentResponse> create(@Valid @RequestBody CreateAgentRequest req) {
        AgentAdminService.Created created = admin.create(req);
        log.info("admin: created agent {} ({})", created.agent().getId(), created.agent().getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateAgentResponse(created.apiKey(), AdminAgentView.of(created.agent())));
    }

    @PutMapping("/{id}")
    public AdminAgentView update(@PathVariable String id, @Valid @RequestBody UpdateAgentRequest req) {
        log.info("admin: updated agent {}", id);
        return AdminAgentView.of(admin.update(id, req));
    }

    @PostMapping("/{id}/rotate-key")
    public KeyResponse rotateKey(@PathVariable String id) {
        log.info("admin: rotated key for agent {}", id);
        return new KeyResponse(admin.rotateKey(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        admin.delete(id);
        log.info("admin: deleted agent {}", id);
    }

    // --- response DTOs ---

    public record CreateAgentResponse(String apiKey, AdminAgentView agent) {
    }

    public record KeyResponse(String apiKey) {
    }

    public record AdminAgentView(String id, String name, long perTxCapAtomic, long dailyBudgetAtomic,
                                 int velocityPerMinute, int minReputation,
                                 List<String> allowedMerchants, List<String> allowedAssets) {
        static AdminAgentView of(AgentEntity a) {
            return new AdminAgentView(a.getId(), a.getName(), a.getPerTxCapAtomic(), a.getDailyBudgetAtomic(),
                    a.getVelocityPerMinute(), a.getMinReputation(),
                    a.getAllowedMerchants().stream().sorted().toList(),
                    a.getAllowedAssets().stream().sorted().toList());
        }
    }
}
