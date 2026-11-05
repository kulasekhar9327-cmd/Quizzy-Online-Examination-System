package com.example.online.exam;

import com.example.online.exam.entity.Question;
import com.example.online.exam.repository.QuestionRepository;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Controller
public class QuestionAdminController {

    private final QuestionRepository questionRepository;

    // Added Java to the allowed subjects
    private static final Set<String> ALLOWED_SUBJECTS =
            Set.of("java", "python", "sql",
                   "aptitude", "mock-tests");

    private static final Set<String> ALLOWED_ANSWERS =
            Set.of("A", "B", "C", "D");

    public QuestionAdminController(
            QuestionRepository questionRepository) {
        this.questionRepository = questionRepository;
    }

    @GetMapping("/admin/questions")
    public String showQuestionPage(
            HttpSession session, Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        loadQuestions(model);
        return "admin-questions";
    }

    @PostMapping("/admin/questions")
    public String saveQuestion(
            HttpSession session,
            @RequestParam String subject,
            @RequestParam String questionText,
            @RequestParam String optionA,
            @RequestParam String optionB,
            @RequestParam String optionC,
            @RequestParam String optionD,
            @RequestParam String correctAnswer,
            Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        String error = validateQuestion(
                subject, questionText,
                optionA, optionB, optionC, optionD,
                correctAnswer);

        if (error != null) {
            model.addAttribute("error", error);
            loadQuestions(model);
            return "admin-questions";
        }

        Question question = new Question();

        applyQuestionValues(
                question, subject, questionText,
                optionA, optionB, optionC, optionD,
                correctAnswer);

        questionRepository.save(question);

        model.addAttribute(
                "success", "Question saved successfully!");

        loadQuestions(model);
        return "admin-questions";
    }

    @GetMapping("/admin/questions/{id}/edit")
    public String showEditQuestion(
            @PathVariable Long id,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<Question> found =
                questionRepository.findById(id);

        if (found.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "error", "Question not found.");
            return "redirect:/admin/questions";
        }

        model.addAttribute("editQuestion", found.get());
        loadQuestions(model);
        return "admin-questions";
    }

    @PostMapping("/admin/questions/{id}/edit")
    public String updateQuestion(
            @PathVariable Long id,
            HttpSession session,
            @RequestParam String subject,
            @RequestParam String questionText,
            @RequestParam String optionA,
            @RequestParam String optionB,
            @RequestParam String optionC,
            @RequestParam String optionD,
            @RequestParam String correctAnswer,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<Question> found =
                questionRepository.findById(id);

        if (found.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "error", "Question not found.");
            return "redirect:/admin/questions";
        }

        String error = validateQuestion(
                subject, questionText,
                optionA, optionB, optionC, optionD,
                correctAnswer);

        if (error != null) {
            model.addAttribute("error", error);
            model.addAttribute("editQuestion", found.get());
            loadQuestions(model);
            return "admin-questions";
        }

        Question question = found.get();

        applyQuestionValues(
                question, subject, questionText,
                optionA, optionB, optionC, optionD,
                correctAnswer);

        questionRepository.save(question);

        redirectAttributes.addFlashAttribute(
                "success", "Question updated successfully!");

        return "redirect:/admin/questions";
    }

    @PostMapping("/admin/questions/{id}/delete")
    public String deleteQuestion(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<Question> found =
                questionRepository.findById(id);

        if (found.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "error", "Question not found.");
        } else {
            questionRepository.delete(found.get());

            redirectAttributes.addFlashAttribute(
                    "success", "Question deleted successfully.");
        }

        return "redirect:/admin/questions";
    }

    private String validateQuestion(
            String subject,
            String questionText,
            String optionA,
            String optionB,
            String optionC,
            String optionD,
            String correctAnswer) {

        String key = subject.trim().toLowerCase();
        String answer = correctAnswer.trim().toUpperCase();

        List<String> options = List.of(
                optionA.trim(),
                optionB.trim(),
                optionC.trim(),
                optionD.trim());

        if (!ALLOWED_SUBJECTS.contains(key)
                || questionText.isBlank()
                || options.stream().anyMatch(String::isBlank)
                || !ALLOWED_ANSWERS.contains(answer)) {

            return "Please provide a valid subject, question, "
                    + "four options, and correct answer.";
        }

        return null;
    }

    private void applyQuestionValues(
            Question question,
            String subject,
            String questionText,
            String optionA,
            String optionB,
            String optionC,
            String optionD,
            String correctAnswer) {

        question.setSubject(subject.trim().toLowerCase());
        question.setQuestionText(questionText.trim());
        question.setOptionA(optionA.trim());
        question.setOptionB(optionB.trim());
        question.setOptionC(optionC.trim());
        question.setOptionD(optionD.trim());
        question.setCorrectAnswer(
                correctAnswer.trim().toUpperCase());
    }

    private void loadQuestions(Model model) {
        model.addAttribute(
                "questions", questionRepository.findAll());
    }

    private boolean isAdmin(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(
                        session.getAttribute("quizzyAdmin"));
    }
}
