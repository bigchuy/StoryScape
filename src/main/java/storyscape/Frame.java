package storyscape;

import java.util.Arrays;

/** One routine activation record: locals, evaluation stack and return information. */
final class Frame {
    int returnPC;
    /** Variable that receives the return value, or -1 if the result is discarded. */
    int storeVar = -1;
    int argCount;
    int[] locals = new int[0];
    int[] stack = new int[16];
    int sp;

    void push(int value) {
        if (sp == stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
        stack[sp++] = value & 0xFFFF;
    }

    int pop() {
        if (sp == 0) throw new ZMachineException("Stack underflow");
        return stack[--sp];
    }

    int peek() {
        if (sp == 0) throw new ZMachineException("Stack underflow");
        return stack[sp - 1];
    }

    /** Replace the top of stack (pushes if the stack is empty). */
    void poke(int value) {
        if (sp == 0) push(value);
        else stack[sp - 1] = value & 0xFFFF;
    }

    Frame copy() {
        Frame f = new Frame();
        f.returnPC = returnPC;
        f.storeVar = storeVar;
        f.argCount = argCount;
        f.locals = locals.clone();
        f.stack = stack.clone();
        f.sp = sp;
        return f;
    }
}
