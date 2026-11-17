#include <csignal>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

#include <poll.h>
#include <termios.h>
#include <unistd.h>

static volatile sig_atomic_t interrupted = 0;

static void HandleInterrupt(int)
{
    interrupted = 1;
}

class TerminalMode
{
public:
    TerminalMode() : active(false)
    {
        if (tcgetattr(STDIN_FILENO, &original) != 0)
            return;
        termios settings = original;
        settings.c_lflag &= ~(ICANON | ECHO);
        settings.c_iflag &= ~(ICRNL | IXON);
        settings.c_cc[VMIN] = 1;
        settings.c_cc[VTIME] = 0;
        active = tcsetattr(STDIN_FILENO, TCSANOW, &settings) == 0;
    }

    ~TerminalMode()
    {
        if (active)
            tcsetattr(STDIN_FILENO, TCSANOW, &original);
    }

private:
    bool active;
    termios original;
};

class CTec2kConPanel;
#include "tec2kvm.h"

class CTec2kConPanel
{
public:
    CTec2kConPanel() : console(0), vm(0), eofPolls(0) {}

    void Attach(CTec2kConsole &newConsole, CTec2kVM &newVm)
    {
        console = &newConsole;
        vm = &newVm;
    }

    void DoMessageLoop()
    {
        if (interrupted || eofPolls >= 1024) {
            vm->Stop();
            return;
        }
        if (eofPolls > 0) {
            ++eofPolls;
            return;
        }
        if (console->PortIn(1) & 2)
            return;
        pollfd input = {STDIN_FILENO, POLLIN, 0};
        if (poll(&input, 1, 1) <= 0)
            return;
        char key;
        if (read(STDIN_FILENO, &key, 1) != 1) {
            eofPolls = 1;
            return;
        }
        if (key == '\004') {
            vm->Stop();
            return;
        }
        if (key == '\n')
            key = '\r';
        if (key == '\177')
            key = '\b';
        console->KeyPressed(key);
    }

    void WriteTerminalByte(char value)
    {
        if (value == '\b')
            std::fputs("\b \b", stdout);
        else if (value != '\033')
            std::fputc(static_cast<unsigned char>(value), stdout);
    }

    // The course's monitor commands use the console, not GUI file transfer.
    void UpdateAChar(int, int, char) {}
    void ScrollALine() {}
    void ClearScreen() {}
    void ToDoNotify() {}
    void EnableCursorTimer() {}
    void DisableCursorTimer() {}
    void UpdateCursor() {}
    void TransferFinished() {}
    void ShowTransferInfo(int, int, int) {}
    unsigned char MonReadByte() { return 0; }
    void MonWriteByte(unsigned char) {}

private:
    CTec2kConsole *console;
    CTec2kVM *vm;
    int eofPolls;
};

#include "tec2kvm.inc"

int main(int argc, char **argv)
{
    if (argc != 2) {
        std::fprintf(stderr, "Usage: tec-monitor MONITOR16.COD\n");
        return 1;
    }
    std::ifstream rom(argv[1], std::ios::binary);
    std::vector<char> image((std::istreambuf_iterator<char>(rom)),
                            std::istreambuf_iterator<char>());
    if (image.empty() || image.size() % 2 != 0 || image.size() > 0x4000) {
        std::fprintf(stderr, "Invalid 16-bit monitor ROM: %s\n", argv[1]);
        return 1;
    }

    std::setvbuf(stdout, 0, _IONBF, 0);
    std::signal(SIGINT, HandleInterrupt);
    std::signal(SIGTERM, HandleInterrupt);
    TerminalMode mode;
    CTec2kConPanel terminal;
    CTec2kConsole console(&terminal);
    CTec2kVM16 vm(&console);
    terminal.Attach(console, vm);
    for (int reg = 0; reg < 16; ++reg)
        vm.SetRegister(reg, 0);
    vm.WriteToMemory(0, static_cast<int>(image.size() / 2), &image[0]);
    vm.Run(0);
    if (isatty(STDOUT_FILENO))
        std::fputc('\n', stdout);
}
