CC      ?= gcc
CFLAGS  ?= -std=c11 -Wall -Wextra -O2 -Iinclude
LDFLAGS ?= -lm

SRC = src/physiology.c
CLI = src/cli.c

.PHONY: all test clean

all: thinair-sim

thinair-sim: $(SRC) $(CLI) include/thinair.h include/ta_generated.h
	$(CC) $(CFLAGS) -o $@ $(SRC) $(CLI) $(LDFLAGS)

test: thinair-sim
	./thinair-sim json
	./thinair-sim profile

clean:
	rm -f thinair-sim
