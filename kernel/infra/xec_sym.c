#include "xec_sym.h"
#include "symbol_resolver.h"

struct cred *(*xec_prepare_kernel_cred)(const struct cred *daemon);
struct task_struct *(*xec_find_get_task_by_vpid)(pid_t nr);
void (*xec_iov_iter_kvec)(struct iov_iter *i, unsigned int direction,
                          const struct kvec *kvec, unsigned long nr_segs, size_t count);
void *(*xec_memdup_user)(const void __user *src, size_t len);
char *(*xec_strnchr)(const char *s, size_t count, int c);
loff_t (*xec_vfs_llseek)(struct file *file, loff_t offset, int whence);
void (*xec_call_rcu)(struct rcu_head *head, rcu_callback_t func);
int (*xec_mutex_trylock)(struct mutex *lock);
unsigned long (*xec___msecs_to_jiffies)(const unsigned int m);
unsigned long *xec_jiffies;
struct mutex *xec_module_mutex;

static const char *const xec_sym_names[] = {
    "prepare_kernel_cred", "find_get_task_by_vpid", "iov_iter_kvec",
    "memdup_user",         "strnchr",                "vfs_llseek",
    "call_rcu",            "mutex_trylock",          "__msecs_to_jiffies",
    "jiffies",             "module_mutex",
};

#define XEC_SYM_COUNT (sizeof(xec_sym_names) / sizeof(xec_sym_names[0]))

int xec_sym_init(void)
{
    unsigned long addrs[XEC_SYM_COUNT];
    int i, n;

    for (i = 0; i < (int)XEC_SYM_COUNT; i++)
        addrs[i] = 0;
    n = ksu_lookup_kernel_symbols(xec_sym_names, addrs, XEC_SYM_COUNT);
    if (n != (int)XEC_SYM_COUNT)
        return -ENOENT;
    for (i = 0; i < (int)XEC_SYM_COUNT; i++) {
        if (!addrs[i])
            return -ENOENT;
    }
    xec_prepare_kernel_cred = (void *)addrs[0];
    xec_find_get_task_by_vpid = (void *)addrs[1];
    xec_iov_iter_kvec = (void *)addrs[2];
    xec_memdup_user = (void *)addrs[3];
    xec_strnchr = (void *)addrs[4];
    xec_vfs_llseek = (void *)addrs[5];
    xec_call_rcu = (void *)addrs[6];
    xec_mutex_trylock = (void *)addrs[7];
    xec___msecs_to_jiffies = (void *)addrs[8];
    xec_jiffies = (unsigned long *)addrs[9];
    xec_module_mutex = (struct mutex *)addrs[10];
    pr_info("xec_kernel: 11 runtime symbols resolved\n");
    return 0;
}
