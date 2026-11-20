#ifndef __KSU_XEC_SYM_H
#define __KSU_XEC_SYM_H

#include <linux/types.h>
#include <linux/cred.h>
#include <linux/sched.h>
#include <linux/uio.h>
#include <linux/fs.h>
#include <linux/rcupdate.h>
#include <linux/mutex.h>
#include <linux/jiffies.h>

/* 运行时解析的 11 个符号（250 表缺的那些），避免 ELF 里留 UND。 */
extern struct cred *(*xec_prepare_kernel_cred)(const struct cred *daemon);
extern struct task_struct *(*xec_find_get_task_by_vpid)(pid_t nr);
extern void (*xec_iov_iter_kvec)(struct iov_iter *i, unsigned int direction,
                                 const struct kvec *kvec, unsigned long nr_segs,
                                 size_t count);
extern void *(*xec_memdup_user)(const void __user *src, size_t len);
extern char *(*xec_strnchr)(const char *s, size_t count, int c);
extern loff_t (*xec_vfs_llseek)(struct file *file, loff_t offset, int whence);
extern void (*xec_call_rcu)(struct rcu_head *head, rcu_callback_t func);
extern int (*xec_mutex_trylock)(struct mutex *lock);
extern unsigned long (*xec___msecs_to_jiffies)(const unsigned int m);
extern unsigned long *xec_jiffies;
extern struct mutex *xec_module_mutex;

int xec_sym_init(void);

#endif
