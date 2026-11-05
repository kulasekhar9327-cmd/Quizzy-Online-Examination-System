
package com.example.online.exam;

import com.example.online.exam.entity.ExamSchedule;
import com.example.online.exam.entity.ScheduledExamQuestion;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.ScheduledExamQuestionRepository;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@Controller
public class ScheduledExamQuestionAdminController {

    private final ExamScheduleRepository scheduleRepository;
    private final ScheduledExamQuestionRepository questionRepository;

    public ScheduledExamQuestionAdminController(
            ExamScheduleRepository scheduleRepository,
            ScheduledExamQuestionRepository questionRepository) {
        this.scheduleRepository = scheduleRepository;
        this.questionRepository = questionRepository;
    }

    // Display questions for a scheduled exam
    @GetMapping("/admin/schedules/{id}/questions")
    public String showQuestions(
            @PathVariable Long id,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<ExamSchedule> result = scheduleRepository.findById(id);

        if (result.isEmpty()) {
            return "redirect:/admin/schedules?notfound";
        }

        model.addAttribute("exam", result.get());
        model.addAttribute(
                "questions",
                questionRepository.findByExamSchedule_IdOrderByIdAsc(id));

        return "admin-scheduled-questions";
    }

    // Add a question
    @PostMapping("/admin/schedules/{id}/questions")
    public String addQuestion(
            @PathVariable Long id,
            HttpSession session,
            @RequestParam String questionText,
            @RequestParam String optionA,
            @RequestParam String optionB,
            @RequestParam String optionC,
            @RequestParam String optionD,
            @RequestParam String correctAnswer,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<ExamSchedule> result = scheduleRepository.findById(id);

        if (result.isEmpty()) {
            return "redirect:/admin/schedules?notfound";
        }

        if (isBlank(questionText, optionA, optionB, optionC, optionD)
                || correctAnswer == null
                || !correctAnswer.matches("[A-D]")) {

            redirectAttributes.addFlashAttribute(
                    "error",
                    "Complete every field and select A, B, C, or D.");

            return "redirect:/admin/schedules/" + id + "/questions";
        }

        ScheduledExamQuestion question = new ScheduledExamQuestion();
        question.setExamSchedule(result.get());
        question.setQuestionText(questionText.trim());
        question.setOptionA(optionA.trim());
        question.setOptionB(optionB.trim());
        question.setOptionC(optionC.trim());
        question.setOptionD(optionD.trim());
        question.setCorrectAnswer(correctAnswer);

        questionRepository.save(question);

        redirectAttributes.addFlashAttribute(
                "success", "Question added successfully.");

        return "redirect:/admin/schedules/" + id + "/questions";
    }

    // Display the edit form with existing question details
    @GetMapping(
            "/admin/schedules/{scheduleId}/questions/{questionId}/edit")
    public String showEditQuestion(
            @PathVariable Long scheduleId,
            @PathVariable Long questionId,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<ExamSchedule> examResult =
                scheduleRepository.findById(scheduleId);

        Optional<ScheduledExamQuestion> questionResult =
                questionRepository.findById(questionId);

        if (examResult.isEmpty()
                || questionResult.isEmpty()
                || !questionResult.get()
                        .getExamSchedule().getId().equals(scheduleId)) {

            return "redirect:/admin/schedules";
        }

        model.addAttribute("exam", examResult.get());
        model.addAttribute("question", questionResult.get());

        return "admin-edit-scheduled-question";
    }

    // Save edited question details
    @PostMapping(
            "/admin/schedules/{scheduleId}/questions/{questionId}/edit")
    public String updateQuestion(
            @PathVariable Long scheduleId,
            @PathVariable Long questionId,
            HttpSession session,
            @RequestParam String questionText,
            @RequestParam String optionA,
            @RequestParam String optionB,
            @RequestParam String optionC,
            @RequestParam String optionD,
            @RequestParam String correctAnswer,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<ExamSchedule> examResult =
                scheduleRepository.findById(scheduleId);

        Optional<ScheduledExamQuestion> questionResult =
                questionRepository.findById(questionId);

        if (examResult.isEmpty()
                || questionResult.isEmpty()
                || !questionResult.get()
                        .getExamSchedule().getId().equals(scheduleId)) {

            return "redirect:/admin/schedules";
        }

        if (isBlank(questionText, optionA, optionB, optionC, optionD)
                || correctAnswer == null
                || !correctAnswer.matches("[A-D]")) {

            redirectAttributes.addFlashAttribute(
                    "error",
                    "Complete every field and select A, B, C, or D.");

            return "redirect:/admin/schedules/"
                    + scheduleId + "/questions/"
                    + questionId + "/edit";
        }

        ScheduledExamQuestion question = questionResult.get();

        question.setQuestionText(questionText.trim());
        question.setOptionA(optionA.trim());
        question.setOptionB(optionB.trim());
        question.setOptionC(optionC.trim());
        question.setOptionD(optionD.trim());
        question.setCorrectAnswer(correctAnswer);

        questionRepository.save(question);

        redirectAttributes.addFlashAttribute(
                "success", "Question updated successfully.");

        return "redirect:/admin/schedules/"
                + scheduleId + "/questions";
    }

    // Delete a question
    @PostMapping(
            "/admin/schedules/{scheduleId}/questions/{questionId}/delete")
    public String deleteQuestion(
            @PathVariable Long scheduleId,
            @PathVariable Long questionId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<ScheduledExamQuestion> result =
                questionRepository.findById(questionId);

        if (result.isEmpty()
                || !result.get()
                        .getExamSchedule().getId().equals(scheduleId)) {

            return "redirect:/admin/schedules/"
                    + scheduleId + "/questions";
        }

        questionRepository.delete(result.get());

        redirectAttributes.addFlashAttribute(
                "success", "Question deleted successfully.");

        return "redirect:/admin/schedules/"
                + scheduleId + "/questions";
    }

    private boolean isBlank(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdmin(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(
                        session.getAttribute("quizzyAdmin"));
    }
}
