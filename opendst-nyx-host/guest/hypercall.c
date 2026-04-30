/*
 * JNI native implementation of opendst.nyx.guest.Hypercall.
 *
 * Two backends compiled from this file:
 *   libhypercall.so       — real, issues the nyx-lite int3 ABI
 *   libhypercall_stub.so  — stub (-DSTUB), prints to stderr; for local testing without a VM
 *
 * nyx-lite hypercall ABI (examples/test_guest_runner.rs):
 *   int 3
 *   RAX = NYX_LITE  (0x6574696c2d78796e)
 *   R8  = hypercall_num
 *   R9  = arg1, R10 = arg2, R11 = arg3, R12 = arg4
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>

#define NYX_LITE UINT64_C(0x6574696c2d78796e)

static void do_hypercall(uint64_t num, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4) {
#ifndef STUB
    register uint64_t r_nyx __asm__("rax") = NYX_LITE;
    register uint64_t r_num __asm__("r8")  = num;
    register uint64_t r_a1  __asm__("r9")  = a1;
    register uint64_t r_a2  __asm__("r10") = a2;
    register uint64_t r_a3  __asm__("r11") = a3;
    register uint64_t r_a4  __asm__("r12") = a4;
    __asm__ volatile (
        "int $3"
        : "+r"(r_nyx)
        : "r"(r_num), "r"(r_a1), "r"(r_a2), "r"(r_a3), "r"(r_a4)
        : "memory"
    );
#else
    fprintf(stderr, "[hypercall-stub] num=0x%lx a1=0x%lx a2=0x%lx a3=0x%lx a4=0x%lx\n",
            (unsigned long)num, (unsigned long)a1,
            (unsigned long)a2, (unsigned long)a3, (unsigned long)a4);
#endif
}


JNIEXPORT void JNICALL Java_opendst_nyx_guest_Hypercall_call(
        JNIEnv *env, jclass cls,
        jlong num, jlong a1, jlong a2, jlong a3, jlong a4)
{
    (void)env; (void)cls;
    do_hypercall((uint64_t)num, (uint64_t)a1, (uint64_t)a2, (uint64_t)a3, (uint64_t)a4);
}

JNIEXPORT void JNICALL Java_opendst_nyx_guest_Hypercall_callWithName(
        JNIEnv *env, jclass cls,
        jlong num, jstring name, jlong a2, jlong a3, jlong a4)
{
    (void)cls;
    const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
    if (cname == NULL) return;
    do_hypercall((uint64_t)num, (uint64_t)(uintptr_t)cname, (uint64_t)a2, (uint64_t)a3, (uint64_t)a4);
    (*env)->ReleaseStringUTFChars(env, name, cname);
}

JNIEXPORT jlong JNICALL Java_opendst_nyx_guest_Hypercall_directAddress(
        JNIEnv *env, jclass cls, jobject buf)
{
    (void)cls;
    return (jlong)(uintptr_t)(*env)->GetDirectBufferAddress(env, buf);
}
