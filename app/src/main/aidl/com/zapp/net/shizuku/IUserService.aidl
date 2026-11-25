package com.zapp.net.shizuku;

/**
 * The binder we call into. Shizuku loads [UserServiceHost] inside its own server process,
 * which runs as uid `shell`, so whatever this method executes has adb rights.
 *
 * The two reserved transaction ids are Shizuku's own contract: 16777114 = destroy and
 * 16777113 = exit. Everything above them is ours.
 */
interface IUserService {
    void destroy() = 16777114;
    void exit() = 16777113;

    /** Runs one `sh -c` command. Answer is "<exit code>\n<combined output>". */
    String run(String cmd) = 1;
}
