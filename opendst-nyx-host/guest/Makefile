JAVA_HOME   ?= $(shell dirname $(shell dirname $(shell readlink -f $(shell which java))))
JNI_INCLUDES = -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux

JAVAC       = $(JAVA_HOME)/bin/javac
JAR         = $(JAVA_HOME)/bin/jar

SRC_DIR     = src
BUILD_DIR   = build
CLASSES_DIR = $(BUILD_DIR)/classes
LIB_DIR     = $(BUILD_DIR)/lib

JAVA_SOURCES = $(shell find $(SRC_DIR) -name "*.java")

.PHONY: all clean stub

all: $(LIB_DIR)/libhypercall.so $(BUILD_DIR)/nyx-guest.jar

stub: $(LIB_DIR)/libhypercall_stub.so $(BUILD_DIR)/nyx-guest.jar

# Compile Java sources
$(CLASSES_DIR)/.javac-stamp: $(JAVA_SOURCES)
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -d $(CLASSES_DIR) $(JAVA_SOURCES)
	touch $@

# Package into a JAR
$(BUILD_DIR)/nyx-guest.jar: $(CLASSES_DIR)/.javac-stamp
	$(JAR) --create --file $@ -C $(CLASSES_DIR) .

# Real native library (nyx-lite hypercall ABI)
$(LIB_DIR)/libhypercall.so: hypercall.c $(CLASSES_DIR)/.javac-stamp
	mkdir -p $(LIB_DIR)
	gcc -shared -fPIC -O2 $(JNI_INCLUDES) -o $@ $<

# Stub native library (for local testing without a VM)
$(LIB_DIR)/libhypercall_stub.so: hypercall.c $(CLASSES_DIR)/.javac-stamp
	mkdir -p $(LIB_DIR)
	gcc -shared -fPIC -O2 -DSTUB $(JNI_INCLUDES) -o $@ $<

clean:
	rm -rf $(BUILD_DIR)
