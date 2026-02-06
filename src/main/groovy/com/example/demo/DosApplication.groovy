package com.example.demo

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.bind.annotation.CrossOrigin
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@CrossOrigin(origins ="*")
@RestController
@RequestMapping("/api")
class AuthController {

    @Autowired
    AuthService authService 

    @Autowired
    AttendanceService attendanceService

    @Autowired
    LeaveService leaveService

    @PostMapping("/login")
    Map login(@RequestBody Map body) {
        return authService.login(body.email, body.password)
    }

    @GetMapping("/attendance/{userId}")
    List<Map<String, Object>> getAttendance(@PathVariable("userId") Integer userId) {
        return attendanceService.getAttendanceList(userId)
    }


    // ===== SIGN IN =====
    @PostMapping("/attendance/signin")
    Map signIn(@RequestBody Map body) {
        return attendanceService.signIn(body.userId)
    }

    // ===== SIGN OUT =====
    @PostMapping("/attendance/signout")
    Map signOut(@RequestBody Map body) {
        return attendanceService.signOut(body.userId)
    }

    // ===== LEAVE: LIST =====
    @GetMapping("/leave/{userId}")
    List<Map<String, Object>> getLeaveList(@PathVariable("userId") Integer userId) {
        return leaveService.getLeaveList(userId)
    }

    // ===== LEAVE: APPLY =====
    @PostMapping("/leave/apply")
    Map applyLeave(@RequestBody Map body) {
        return leaveService.applyLeave(
            body.userId as int,
            body.leaveTypeId as int,
            body.dateFrom as String,
            body.dateTo as String,
            body.reason as String
        )
    }

    // ===== LEAVE: BALANCE =====
    @GetMapping("/leave/balance/{userId}")
    List<Map<String, Object>> getLeaveBalance(@PathVariable("userId") Integer userId) {
        return leaveService.getLeaveBalance(userId)
    }
}

/////////////////////// SERVICES ///////////////////////

@Service
class AuthService {

    @Autowired
    JdbcTemplate jdbcTemplate

    Map login(String email, String password) {
        def user = jdbcTemplate.queryForList(
            "SELECT * FROM users WHERE email = ? AND password = ?",
            email, password
        )

        if (user.size() > 0) {
            return [status: "success", message: "Login OK", user: user]
        } else {
            return [status: "error", message: "Wrong login"]
        }
    }
}

@Service
class AttendanceService {

    @Autowired
    JdbcTemplate jdbcTemplate

    // ===== LIST =====
    List<Map<String, Object>> getAttendanceList(Integer userId) {
        return jdbcTemplate.queryForList(
            """
            SELECT a.*, s.code AS status_name
            FROM attendance a
            JOIN attendance_status_ref s ON a.status = s.id
            WHERE a.user_id = ?
            ORDER BY a.sign_in DESC
            """,
            userId
        )
    }


    // ===== SIGN IN =====
    Map signIn(int userId) {
        jdbcTemplate.update(
            """
            INSERT INTO attendance(user_id, sign_in, status)
            VALUES (?, NOW(), 1)
            """,
            userId
        )

        return [status: "success", message: "Signed in"]
    }

    // ===== SIGN OUT =====
    Map signOut(int userId) {
        jdbcTemplate.update(
            """
            UPDATE attendance
            SET sign_out = NOW(),
                status = 2
            WHERE user_id = ?
            AND sign_out IS NULL
            AND status = 1
            """,
            userId
        )

        return [status: "success", message: "Signed out"]
    }
}

@Service
class LeaveService {

    @Autowired
    JdbcTemplate jdbcTemplate

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ===== LIST =====
    List<Map<String, Object>> getLeaveList(Integer userId) {
        return jdbcTemplate.queryForList(
            """
            SELECT la.*, lt.name AS leave_type, ls.name AS status_name
            FROM leave_applications la
            JOIN leave_types lt ON la.leave_type_id = lt.id
            JOIN leave_status ls ON la.status = ls.id
            WHERE la.user_id = ?
            ORDER BY la.date_from DESC
            """,
            userId
        )
    }

    // ===== APPLY LEAVE =====
    Map applyLeave(int userId, int leaveTypeId, String dateFromStr, String dateToStr, String reason) {
        LocalDate from = LocalDate.parse(dateFromStr, formatter)
        LocalDate to = LocalDate.parse(dateToStr, formatter)

        // ===== Count days excluding weekends & public holidays =====
        int days = 0
        LocalDate d = from
        while (!d.isAfter(to)) {
            def isWeekend = d.getDayOfWeek().getValue() >= 6
            def isHoliday = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_holidays WHERE holiday_date = ?",
                Integer.class,
                d
            ) > 0
            if (!isWeekend && !isHoliday) {
                days++
            }
            d = d.plusDays(1)
        }

        // ===== Get balance =====
        int deductType = (leaveTypeId == 2 ? 1 : leaveTypeId) // Emergency leave deduct annual
        Map balance = jdbcTemplate.queryForMap(
            "SELECT * FROM leave_balance WHERE user_id = ? AND leave_type_id = ?",
            userId, deductType
        )

        if (balance == null || balance.balance < days) {
            return [status: "error", message: "Insufficient leave balance"]
        }

        // ===== Insert leave application =====
        jdbcTemplate.update(
            """
            INSERT INTO leave_applications(user_id, leave_type_id, date_from, date_to, status, day_count, reason)
            VALUES (?, ?, ?, ?, 1, ?, ?)
            """,
            userId, leaveTypeId, from, to, days, reason
        )

        // ===== Update leave balance =====
        jdbcTemplate.update(
            """
            UPDATE leave_balance
            SET used = used + ?, balance = balance - ?
            WHERE user_id = ? AND leave_type_id = ?
            """,
            days, days, userId, deductType
        )

        return [status: "success", message: "Leave applied"]
    }

    // ===== GET LEAVE BALANCE =====
    List<Map<String, Object>> getLeaveBalance(Integer userId) {
        return jdbcTemplate.queryForList(
            """
            SELECT lb.*, lt.name AS leave_type
            FROM leave_balance lb
            JOIN leave_types lt ON lb.leave_type_id = lt.id
            WHERE lb.user_id = ?
            """,
            userId
        )
    }
}
