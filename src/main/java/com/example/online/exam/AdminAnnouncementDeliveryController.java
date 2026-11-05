package com.example.online.exam;

import com.example.online.exam.entity.AnnouncementDeliveryStatus;
import com.example.online.exam.repository.AnnouncementDeliveryRepository;
import com.example.online.exam.repository.AnnouncementDeliveryRepository.DeliveryTrackingRow;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.Locale;

@Controller
public class AdminAnnouncementDeliveryController {

    private static final int PAGE_SIZE = 25;

    private final AnnouncementDeliveryRepository deliveryRepository;

    public AdminAnnouncementDeliveryController(
            AnnouncementDeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @GetMapping("/admin/announcement-deliveries")
    public String showDeliveries(
            HttpSession session,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        if (session == null
                || !Boolean.TRUE.equals(session.getAttribute("quizzyAdmin"))) {
            return "redirect:/admin/login";
        }
        AnnouncementDeliveryStatus selectedStatus = parseStatus(status);
        if (!status.isBlank() && selectedStatus == null) {
            model.addAttribute("error", "Choose a valid delivery status.");
        }
        int safePage = Math.max(0, Math.min(page, 100_000));
        Page<DeliveryTrackingRow> deliveries =
                deliveryRepository.findDeliveryTracking(
                        selectedStatus,
                        PageRequest.of(
                                safePage,
                                PAGE_SIZE,
                                Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("deliveries", deliveries);
        model.addAttribute(
                "selectedStatus",
                selectedStatus == null ? "" : selectedStatus.name());
        model.addAttribute("statuses", Arrays.stream(
                        AnnouncementDeliveryStatus.values())
                .map(Enum::name)
                .toList());
        return "admin-announcement-deliveries";
    }

    private AnnouncementDeliveryStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AnnouncementDeliveryStatus.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
