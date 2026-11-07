package ma.jomhor.tenancy.adapters.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import ma.jomhor.tenancy.application.StaffService;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffMember;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer: /org/staff, /org/staff/{id}, /org/staff/{id}/pin. ADMIN/OPS only. The PIN verifier
 * never leaves through here; the plain PIN only in the create and reset responses.
 */
@RestController
class OrgStaffController {

    record StaffCreate(@NotNull String name, @NotNull StaffRole role, String phone) {}

    /** Partial: null = unchanged. */
    record StaffPatch(String name, Boolean active) {}

    record StaffDto(UUID id, String name, StaffRole role, boolean active, boolean hasPin) {
        static StaffDto of(StaffMember s) {
            return new StaffDto(s.id(), s.name(), s.role(), s.active(), s.hasPin());
        }
    }

    /** {@code pin} absent for a CASHIER (no PIN). */
    record CreatedDto(UUID id, String name, StaffRole role, boolean active, boolean hasPin,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String pin) {
        @Override
        public String toString() {
            return "CreatedDto[id=" + id + ", role=" + role + ", pin=****]";
        }
    }

    record PinDto(String pin) {
        @Override
        public String toString() {
            return "PinDto[pin=****]";
        }
    }

    private final StaffService staff;

    OrgStaffController(StaffService staff) {
        this.staff = staff;
    }

    @GetMapping("/org/staff")
    List<StaffDto> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return staff.list(org.adminOrOps().organizerId()).stream().map(StaffDto::of).toList();
    }

    @PostMapping("/org/staff")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedDto create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @Valid @RequestBody StaffCreate body) {
        var c = staff.create(org.adminOrOps().organizerId(), body.name(), body.role(), body.phone());
        StaffMember s = c.staff();
        return new CreatedDto(s.id(), s.name(), s.role(), s.active(), s.hasPin(), c.pin());
    }

    @PatchMapping("/org/staff/{id}")
    StaffDto update(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                    @RequestBody StaffPatch body) {
        return StaffDto.of(staff.update(org.adminOrOps().organizerId(), id, body.name(), body.active()));
    }

    @PostMapping("/org/staff/{id}/pin")
    PinDto resetPin(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        org.adminOrOps();
        return new PinDto(staff.resetPin(org.organizerId(), org.userId(), id));
    }
}
