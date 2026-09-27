package com.trialflow.config

import com.trialflow.domain.Campus
import com.trialflow.domain.ClassType
import com.trialflow.domain.CoursePackage
import com.trialflow.domain.Level
import com.trialflow.domain.Role
import com.trialflow.domain.Subject
import com.trialflow.domain.Teacher
import com.trialflow.domain.Textbook
import com.trialflow.domain.UserAccount
import com.trialflow.domain.Classroom
import com.trialflow.service.ClassService
import com.trialflow.store.DataStore
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 演示种子数据:两个校区、共享老师、教室、顾问、校长、课包与正式班级。
 * 测试账号见 README。
 */
@Component
@ConditionalOnProperty(name = ["app.seed.enabled"], havingValue = "true", matchIfMissing = true)
class SeedData(
    private val store: DataStore,
    private val classService: ClassService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (store.campuses.isNotEmpty()) return

        // ---- 校区与教室 ----
        store.campuses["CAMP-ZGC"] = Campus("CAMP-ZGC", "中关村校区")
        store.campuses["CAMP-WJ"] = Campus("CAMP-WJ", "望京校区")
        store.classrooms["ROOM-ZGC-1"] = Classroom("ROOM-ZGC-1", "CAMP-ZGC", "中关村-101(小)", 4)
        store.classrooms["ROOM-ZGC-2"] = Classroom("ROOM-ZGC-2", "CAMP-ZGC", "中关村-102(中)", 8)
        store.classrooms["ROOM-WJ-1"] = Classroom("ROOM-WJ-1", "CAMP-WJ", "望京-201(中)", 8)

        // ---- 账号(测试账号,用 X-Actor-Id 头标识身份) ----
        store.users["ADMIN"] = UserAccount("ADMIN", "系统管理员", Role.ADMIN)
        store.users["P001"] = UserAccount("P001", "赵校长", Role.PRINCIPAL, setOf("CAMP-ZGC", "CAMP-WJ"))
        store.users["CS001"] = UserAccount("CS001", "王顾问", Role.CONSULTANT, setOf("CAMP-ZGC"))
        store.users["CS002"] = UserAccount("CS002", "李顾问", Role.CONSULTANT, setOf("CAMP-WJ"))

        // ---- 老师:T001 为两校区共享老师 ----
        val slots = mutableSetOf<String>()
        val today = LocalDate.now()
        for (d in 1..14L) {
            val date = today.plusDays(d)
            for (hour in listOf(10, 14, 16, 19)) {
                slots.add("${date}T${hour.toString().padStart(2, '0')}:00")
            }
        }
        store.teachers["T001"] = Teacher(
            "T001", "张敏老师",
            subjects = setOf(Subject.MATH),
            levels = setOf(Level.ELEMENTARY, Level.INTERMEDIATE, Level.ADVANCED),
            textbooks = setOf(Textbook.PEP, Textbook.BNU),
            campusIds = setOf("CAMP-ZGC", "CAMP-WJ"), // 多校区共享
            availableSlots = slots.toMutableSet(),
        )
        store.teachers["T002"] = Teacher(
            "T002", "刘洋老师",
            subjects = setOf(Subject.ENGLISH),
            levels = setOf(Level.BEGINNER, Level.ELEMENTARY, Level.INTERMEDIATE),
            textbooks = setOf(Textbook.PEP),
            campusIds = setOf("CAMP-ZGC"),
            availableSlots = slots.toMutableSet(),
        )
        store.teachers["T003"] = Teacher(
            "T003", "陈静老师",
            subjects = setOf(Subject.MATH, Subject.PHYSICS),
            levels = setOf(Level.INTERMEDIATE, Level.ADVANCED),
            textbooks = setOf(Textbook.SJ, Textbook.PEP),
            campusIds = setOf("CAMP-WJ"),
            availableSlots = slots.toMutableSet(),
        )

        // ---- 课包 ----
        store.packages["PKG-MATH-48"] = CoursePackage(
            "PKG-MATH-48", "数学思维 48 课时包", Subject.MATH, Level.INTERMEDIATE, Textbook.PEP,
            lessons = 48, priceCents = 960_000, textbookFeeCents = 30_000,
        )
        store.packages["PKG-MATH-24"] = CoursePackage(
            "PKG-MATH-24", "数学同步 24 课时包", Subject.MATH, Level.ELEMENTARY, Textbook.PEP,
            lessons = 24, priceCents = 480_000, textbookFeeCents = 20_000,
        )
        store.packages["PKG-ENG-24"] = CoursePackage(
            "PKG-ENG-24", "英语听说 24 课时包", Subject.ENGLISH, Level.ELEMENTARY, Textbook.PEP,
            lessons = 24, priceCents = 520_000, textbookFeeCents = 25_000,
        )

        // ---- 正式班级(每周固定课次,未来 8 周) ----
        fun nextWeekdays(dow: DayOfWeek, hour: Int, weeks: Int): Set<String> {
            var date = today.plusDays(1)
            while (date.dayOfWeek != dow) date = date.plusDays(1)
            return (0 until weeks).map { w ->
                "${date.plusWeeks(w.toLong())}T${hour.toString().padStart(2, '0')}:00"
            }.toSet()
        }
        classService.createClassGroup(
            "CAMP-ZGC", Subject.MATH, Level.INTERMEDIATE, Textbook.PEP, ClassType.SMALL,
            "T001", "ROOM-ZGC-1", 4, nextWeekdays(DayOfWeek.SATURDAY, 10, 8), "seed",
        )
        classService.createClassGroup(
            "CAMP-ZGC", Subject.MATH, Level.ELEMENTARY, Textbook.PEP, ClassType.SMALL,
            "T001", "ROOM-ZGC-2", 6, nextWeekdays(DayOfWeek.SUNDAY, 14, 8), "seed",
        )
        classService.createClassGroup(
            "CAMP-ZGC", Subject.ENGLISH, Level.ELEMENTARY, Textbook.PEP, ClassType.SMALL,
            "T002", "ROOM-ZGC-2", 6, nextWeekdays(DayOfWeek.SATURDAY, 14, 8), "seed",
        )
        classService.createClassGroup(
            "CAMP-WJ", Subject.MATH, Level.INTERMEDIATE, Textbook.PEP, ClassType.MEDIUM,
            "T003", "ROOM-WJ-1", 8, nextWeekdays(DayOfWeek.SATURDAY, 10, 8), "seed",
        )
        log.info(
            "种子数据就绪: 校区={}, 教室={}, 老师={}, 课包={}, 班级={}",
            store.campuses.size, store.classrooms.size, store.teachers.size,
            store.packages.size, store.classGroups.size,
        )
    }
}
