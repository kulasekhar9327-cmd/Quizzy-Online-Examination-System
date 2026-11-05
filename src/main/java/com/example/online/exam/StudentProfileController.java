package com.example.online.exam;

import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.ExamAttemptSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;
import java.util.regex.Pattern;

@Controller
public class StudentProfileController {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}"
                    + "[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}"
                    + "[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE);

    private final StudentRepository studentRepository;

    public StudentProfileController(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @GetMapping("/profile")
    public String showProfile(HttpSession session, Model model) {
        Student student = authenticatedStudent(session);
        if (student == null) {
            return "redirect:/login";
        }
        addProfile(model, student.getFullName(), student.getEmail());
        return "student-profile";
    }

    @PostMapping("/profile")
    public String updateProfile(
            HttpSession session,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String email,
            Model model) {
        Student student = authenticatedStudent(session);
        if (student == null) {
            return "redirect:/login";
        }

        String normalizedName = fullName == null ? "" : fullName.trim();
        String normalizedEmail = email == null
                ? ""
                : email.trim().toLowerCase(Locale.ROOT);

        if (!validName(normalizedName) || !validEmail(normalizedEmail)) {
            model.addAttribute(
                    "error",
                    "Enter a name of 1 to " + MAX_NAME_LENGTH
                            + " characters and a valid email address.");
            addProfile(model, normalizedName, normalizedEmail);
            return "student-profile";
        }

        if (studentRepository.existsByEmailAndIdNot(
                normalizedEmail, student.getId())) {
            model.addAttribute(
                    "error", "That email address is already in use.");
            addProfile(model, normalizedName, normalizedEmail);
            return "student-profile";
        }

        student.setFullName(normalizedName);
        student.setEmail(normalizedEmail);
        try {
            studentRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException exception) {
            model.addAttribute(
                    "error", "That email address is already in use.");
            addProfile(model, normalizedName, normalizedEmail);
            return "student-profile";
        }

        session.setAttribute("studentName", normalizedName);
        model.addAttribute("success", "Your profile has been updated.");
        addProfile(model, normalizedName, normalizedEmail);
        return "student-profile";
    }

    private Student authenticatedStudent(HttpSession session) {
        Long studentId = ExamAttemptSession.studentId(session);
        if (studentId == null) {
            return null;
        }
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            session.invalidate();
        }
        return student;
    }

    private boolean validName(String name) {
        return name != null
                && !name.isBlank()
                && name.length() <= MAX_NAME_LENGTH;
    }

    private boolean validEmail(String email) {
        return email != null
                && email.length() <= MAX_EMAIL_LENGTH
                && EMAIL_PATTERN.matcher(email).matches();
    }

    private void addProfile(Model model, String fullName, String email) {
        model.addAttribute("fullName", fullName);
        model.addAttribute("email", email);
    }
}
