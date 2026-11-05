
package com.example.online.exam;

import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.StudentRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class StudentAuthController {

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;

    public StudentAuthController(
            StudentRepository studentRepository,
            PasswordEncoder passwordEncoder) {
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/register")
    public String showRegistrationPage() {
        return "register";
    }

    @PostMapping("/register")
    public String registerStudent(
            @RequestParam String fullName,
            @RequestParam String email,
            @RequestParam String password,
            Model model) {

        fullName = fullName.trim();
        email = email.trim().toLowerCase();

        if (fullName.isBlank() || email.isBlank()
                || password.isBlank()) {
            model.addAttribute("error", "All fields are required.");
            return "register";
        }

        if (password.length() < 8) {
            model.addAttribute(
                    "error", "Password must contain at least 8 characters.");
            return "register";
        }

        if (studentRepository.findByEmail(email).isPresent()) {
            model.addAttribute(
                    "error", "This email is already registered.");
            return "register";
        }

        Student student = new Student(
                fullName,
                email,
                passwordEncoder.encode(password));

        studentRepository.save(student);

        model.addAttribute(
                "success", "Registration successful! You can now log in.");

        return "register";
    }

    @GetMapping("/login")
    public String showLoginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String loginStudent(
            @RequestParam String email,
            @RequestParam String password,
            HttpServletRequest request,
            Model model) {

        String normalizedEmail = email.trim().toLowerCase();

        Student student = studentRepository
                .findByEmail(normalizedEmail)
                .orElse(null);

        if (student == null
                || !passwordEncoder.matches(
                        password, student.getPassword())) {
            model.addAttribute(
                    "error", "Invalid email or password.");
            return "login";
        }

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }

        HttpSession newSession = request.getSession(true);
        newSession.setAttribute("studentId", student.getId());
        newSession.setAttribute("studentName", student.getFullName());

        return "redirect:/dashboard";
    }

    @PostMapping("/logout")
    public String logoutStudent(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
