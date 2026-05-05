JAVA_HOME   ?= $(shell dirname $(shell dirname $(shell readlink -f $(shell which java))))

JAVAC       = $(JAVA_HOME)/bin/javac
JAR         = $(JAVA_HOME)/bin/jar

SRC_DIR     = src
BUILD_DIR   = build
CLASSES_DIR = $(BUILD_DIR)/classes
LIB_DIR     = $(BUILD_DIR)/lib

RUST_TARGET = ../target/release
RUST_TARGET_STUB = ../target/debug

JAVA_SOURCES = $(shell find $(SRC_DIR) -name "*.java")

.PHONY: all clean stub

all: $(LIB_DIR)/libhypercall.so $(BUILD_DIR)/nyx-guest.jar

stub: $(LIB_DIR)/libhypercall.so $(BUILD_DIR)/nyx-guest.jar
	cd .. && cargo build -p libhypercall --features stub
	mkdir -p $(LIB_DIR)
	cp $(RUST_TARGET_STUB)/libhypercall.so $(LIB_DIR)/libhypercall.so

# Compile Java sources
$(CLASSES_DIR)/.javac-stamp: $(JAVA_SOURCES)
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -d $(CLASSES_DIR) $(JAVA_SOURCES)
	touch $@

# Package into a JAR
$(BUILD_DIR)/nyx-guest.jar: $(CLASSES_DIR)/.javac-stamp
	$(JAR) --create --file $@ -C $(CLASSES_DIR) .

# Build libhypercall.so via Rust (no gcc needed)
$(LIB_DIR)/libhypercall.so: $(CLASSES_DIR)/.javac-stamp
	cd .. && cargo build -p libhypercall --release
	mkdir -p $(LIB_DIR)
	cp $(RUST_TARGET)/libhypercall.so $(LIB_DIR)/libhypercall.so

clean:
	rm -rf $(BUILD_DIR)
