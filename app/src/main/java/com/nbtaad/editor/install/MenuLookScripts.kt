package com.nbtaad.editor.install

/*
 * The job for aa-menu-look (see [TileScripts] for how a job gets to hu-omap's shell and what its report looks like),
 * written from the steps of the author's install.sh and restore.sh. The label is changed by the kit's aa-look program,
 * which is copied into RAM and run there; it reads the head unit's text.db (or the factory copy kept by an earlier
 * run), writes the new one into RAM and checks it. Only after all checks, and after the factory copy of every file is
 * kept in /opt/aa/menu-look-backup, are the head unit's files replaced. The backup folder has the same layout as the
 * author's, so the zip's own restore.sh can also undo what this did.
 */
object MenuLookScripts {
    enum class Action(val word: String) {
        /** Reads what the head unit has (which brands, whether it is installed). */
        LOOK("look"),
        /** Everything except the replacing: the checks and the label made in RAM. */
        CHECK("check"),
        APPLY("apply"),
        RESTORE("restore"),
    }

    /** A picture to put on the head unit: the file's name in the RAM folder and its place under /opt/hmi/ID5/data/ro/. */
    class Image(val stageName: String, val rel: String)

    fun job(
        action: Action,
        brand: String,
        label: String,
        images: List<Image> = emptyList(),
        programSize: Int = 0,
        root: String = "",
        stage: String = TileScripts.stageThere(),
    ): String {
        require(brand in com.nbtaad.editor.addon.MenuLook.BRANDS) { "brand" }
        require(com.nbtaad.editor.addon.MenuLook.labelProblem(label) == null) { "label" }
        require(root.none { it in "'\"\\\n ¤" } && stage.none { it in "'\"\\\n ¤" })
        for (i in images) {
            require(Regex("""[A-Za-z0-9_.]+""").matches(i.stageName)) { "picture name" }
            require(Regex("""[A-Za-z0-9_./]+\.png""").matches(i.rel) && ".." !in i.rel) { "picture path" }
        }
        val imageLines = images.joinToString("\n") { "check_img ${i(it.stageName)} ${i(it.rel)}" }
        val head = """
            R=$root
            S=¤{1:-$stage}
            exec > ¤S/out 2>&1
            RO=¤R/opt/hmi/ID5/data/ro
            B=¤R/opt/aa/menu-look-backup
            MAN=¤B/files.txt
            SH=¤R/dev/shmem
            X=¤SH/aa-look
            NEW=¤SH/aa-menu-look-text.db
            LIST=¤SH/aa-menu-look-images.txt
            LAB=¤SH/aa-menu-look-labels.log
            BRAND=$brand
            LABEL='$label'
            AS=$programSize
            T=¤RO/¤BRAND/db/text.db
            echo "JOB ${action.word}"
        """
        val body = when (action) {
            Action.LOOK -> "echo \"OK looked\"\necho \"END 0\""
            Action.CHECK -> CHECK_PREP + "\n" + imageLines + "\n" + LABELS + "\n" + CHECK_END
            Action.APPLY -> CHECK_PREP + "\n" + imageLines + "\n" + LABELS + "\n" + APPLY
            Action.RESTORE -> RESTORE
        }
        return TileScripts.sh(head + "\n" + COMMON + "\n" + body + "\nexit 0")
    }

    private fun i(s: String) = "\"$s\""

    private val COMMON = """
        fail() { echo "ERR ¤1"; rm -f ¤X ¤NEW ¤LIST ¤LAB; echo "END 1"; exit 1; }
        size() { Z=¤(wc -c < "¤1" 2>/dev/null); echo ¤Z; }
        [ -d ¤R/opt/hmi ] || fail "This is not the shell of the head unit's hu-omap processor (no /opt/hmi), so nothing was changed."
        for b in bmw bmwm bmwi mini; do
          if [ -f ¤RO/¤b/db/text.db ]; then echo "ST brand-¤b yes"; fi
        done
        if [ -f ¤MAN ]; then
          echo "ST installed yes"
          if [ -f ¤B/brand ]; then ib=""; read ib < ¤B/brand; echo "ST ibrand ¤ib"; fi
          n=0
          while read l; do if [ -n "¤l" ]; then n=¤((n+1)); fi; done < ¤MAN
          echo "ST files ¤n"
        else
          echo "ST installed no"
        fi
        if [ -d ¤R/opt/aa/menu-prima ] || [ -d ¤R/opt/aa/menu-prima-bmw ]; then echo "ST oldkit yes"; fi
    """.trimIndent()

    /** The checks that come before anything is made (the author's install.sh, "checks"). */
    private val CHECK_PREP = """
        [ -f ¤T ] || fail "¤T is not on this head unit: wrong brand for it? Nothing was changed."
        for o in ¤R/opt/aa/menu-prima ¤R/opt/aa/menu-prima-bmw; do
          if [ -d ¤o ]; then fail "An older menu kit is installed (¤o). Remove it first with its own remove script, restart, then try again. Nothing was changed."; fi
        done
        if [ -f ¤B/brand ]; then
          read OLDB < ¤B/brand
          if [ "¤OLDB" != "¤BRAND" ]; then fail "The look is already installed for '¤OLDB'. Restore it first. Nothing was changed."; fi
        fi
        [ -f ¤S/aa-look ] || fail "aa-look did not arrive on the head unit."
        [ "¤(size ¤S/aa-look)" = "¤AS" ] || fail "aa-look arrived with the wrong size. Nothing was changed."
        rm -f ¤X ¤NEW ¤LIST ¤LAB
        # the stage is not executable: copy a program (its execute bit stays), then write the file's content over it
        cp /bin/ls ¤X && cat ¤S/aa-look > ¤X || fail "Cannot copy aa-look into RAM."
        [ "¤(size ¤X)" = "¤AS" ] || fail "The copy of aa-look in RAM has the wrong size."
        : > ¤LIST
        NIMG=0
        check_img() {
          NAME=¤1; REL=¤2
          DST=¤RO/¤REL
          if [ ! -f ¤DST ]; then echo "INFO skipped (no such file on this head unit): ¤REL"; return 0; fi
          A=¤(¤X png ¤S/¤NAME) || fail "¤REL is not a PNG. Nothing was changed."
          O=¤(¤X png ¤DST) || fail "The head unit's ¤REL is not a PNG. Nothing was changed."
          set -- ¤A; AW=¤1; AH=¤2; AT=¤3
          set -- ¤O; OW=¤1; OH=¤2; OT=¤3
          if [ "¤AW" != "¤OW" ] || [ "¤AH" != "¤OH" ]; then
            fail "¤REL is ¤{AW}x¤{AH}, the original is ¤{OW}x¤{OH}. The same size is needed. Nothing was changed."
          fi
          if [ "¤AT" != "¤OT" ]; then echo "INFO ¤REL has colour type ¤AT, the original ¤OT (2 = RGB, 4 = grey+alpha, 6 = RGBA)"; fi
          echo "¤NAME ¤REL" >> ¤LIST
          NIMG=¤((NIMG+1))
        }
    """.trimIndent()

    /** The labels (the author's install.sh, "labels": always from the factory copy once there is one). */
    private val LABELS = """
        echo "INFO pictures to replace: ¤NIMG"
        SRC=¤T
        if [ -f ¤B/ro/¤BRAND/db/text.db ]; then SRC=¤B/ro/¤BRAND/db/text.db; fi
        ¤X labels ¤BRAND ¤SRC ¤NEW "¤LABEL" > ¤LAB 2>&1
        RC=¤?
        LABELS=0
        case ¤RC in
          0) LABELS=1 ;;
          3) echo "INFO labels: nothing to change (already done, or this text.db has other IDs)" ;;
          *) echo "LOG-BEGIN"; cat ¤LAB; echo "LOG-END"; fail "The labels could not be made (code ¤RC). Nothing was changed." ;;
        esac
        echo "LOG-BEGIN"; cat ¤LAB; echo "LOG-END"
        echo "ST labels ¤LABELS"
        echo "ST pictures ¤NIMG"
    """.trimIndent()

    private val CHECK_END = """
        rm -f ¤X ¤NEW ¤LIST ¤LAB
        echo "OK checked: nothing on the head unit was changed"
        echo "END 0"
    """.trimIndent()

    /** The replacing (the author's install.sh, "changes"). */
    private val APPLY = """
        if [ ¤LABELS = 0 ] && [ ¤NIMG = 0 ]; then rm -f ¤X ¤NEW ¤LIST ¤LAB; echo "OK nothing to do"; echo "END 0"; exit 0; fi
        mkdir -p ¤B/ro || fail "Cannot write in /opt/aa. Is the head unit's system partition writable?"
        echo ¤BRAND > ¤B/brand
        : >> ¤MAN
        backup() {
          if [ -f "¤B/ro/¤1" ]; then return 0; fi
          mkdir -p "¤B/ro/¤{1%/*}" || { echo "ERR cannot create the backup folder for ¤1"; return 1; }
          if cp "¤RO/¤1" "¤B/ro/¤1" && ¤X same "¤RO/¤1" "¤B/ro/¤1"; then
            echo "¤1" >> ¤MAN; echo "INFO backup: ¤1"; return 0
          fi
          rm -f "¤B/ro/¤1"; echo "ERR the backup of ¤1 failed"; return 1
        }
        put() {
          D="¤RO/¤1"
          if ¤X same "¤D" "¤2"; then echo "INFO already in place: ¤1"; return 0; fi
          V="¤D.old-¤¤"
          mv "¤D" "¤V" || { echo "ERR cannot rename ¤D"; return 1; }
          if cp "¤2" "¤D" && ¤X same "¤D" "¤2"; then rm -f "¤V"; echo "OK replaced ¤1"; return 0; fi
          echo "ERR the copy of ¤1 failed, the previous file is back"
          rm -f "¤D"; mv "¤V" "¤D"; return 1
        }
        FAIL=0
        if [ ¤LABELS = 1 ]; then
          backup "¤BRAND/db/text.db" && put "¤BRAND/db/text.db" ¤NEW || FAIL=1
        fi
        if [ ¤FAIL = 0 ] && [ ¤NIMG != 0 ]; then
          while read NAME REL; do
            backup "¤REL" && put "¤REL" "¤S/¤NAME" || { FAIL=1; break; }
          done < ¤LIST
        fi
        rm -f ¤X ¤NEW ¤LIST ¤LAB
        if [ ¤FAIL != 0 ]; then
          echo "ERR Stopped on an error. What was already replaced is listed in ¤MAN, and restoring puts it back."
          echo "END 1"
          exit 1
        fi
        echo "OK done"
        echo "END 0"
    """.trimIndent()

    /** The author's restore.sh: every kept factory copy goes back, then the backup folder is removed. */
    private val RESTORE = """
        if [ ! -f ¤MAN ]; then echo "INFO nothing to restore: the look is not installed"; echo "END 0"; exit 0; fi
        FAIL=0
        N=0
        while read REL; do
          [ -n "¤REL" ] || continue
          SRCF="¤B/ro/¤REL"
          DST="¤RO/¤REL"
          if [ ! -f "¤SRCF" ]; then echo "ERR the backup of ¤REL is missing"; FAIL=1; continue; fi
          V="¤DST.new-¤¤"
          if [ -f "¤DST" ]; then mv "¤DST" "¤V" || { echo "ERR cannot rename ¤DST (is the system partition writable?)"; FAIL=1; continue; }; fi
          if cp "¤SRCF" "¤DST" && [ "¤(size "¤DST")" = "¤(size "¤SRCF")" ]; then
            rm -f "¤V"; echo "OK restored ¤REL"; N=¤((N+1))
          else
            echo "ERR the copy of ¤REL failed, the current file is left"
            rm -f "¤DST"; [ -f "¤V" ] && mv "¤V" "¤DST"; FAIL=1
          fi
        done < ¤MAN
        if [ ¤FAIL != 0 ]; then
          echo "ERR Not complete: the backup in ¤B is kept. Fix the error above and restore again."
          echo "END 1"
          exit 1
        fi
        cd /
        rm -rf ¤B
        echo "OK ¤N file(s) are back to the original"
        echo "END 0"
    """.trimIndent()
}
