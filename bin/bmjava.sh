#!/bin/bash
#
# JBoss, Home of Professional Open Source
# Copyright 2009, Red Hat Middleware LLC, and individual contributors
# by the @authors tag. See the copyright.txt in the distribution for a
# full listing of individual contributors.
#
# This is free software; you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as
# published by the Free Software Foundation; either version 2.1 of
# the License, or (at your option) any later version.
#
# This software is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# Lesser General Public License for more details.
# You should have received a copy of the GNU Lesser General Public
# License along with this software; if not, write to the Free
# Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
# 02110-1301 USA, or see the FSF site: http://www.fsf.org.
#
# @authors Andrew Dinn
#
# shell script which starts a java program with the byteman
# agent installed
#
# usage: bmjava [-r rulescript | -b bootjar | -s sysjar | -nl | -nb ]* [--] javaargs
#   -r pass the file whose name follows this flag to the agent as
#      a rule script
#
#   -b pass the file whose name follows this flag to the agent as
#      a jar to be added to the bootstrap classpath
#
#   -s pass the file whose name follows this flag to the agent as
#      a jar to be added to the system classpath
#
#   -nl do not enable the agent listener (it is enabled by default)
#
#   -nb do not add the byteman jar to the bootstrap classpath (it is
#       added by default)
#
#
#   -- optional separator to distinguish trailing arguments
#
#   javaargs trailing arguments to be supplied to the java command
#
# The script employs the java command found in the current execution
# PATH. If BYTEMAN_JAVA_ARGS is set then this is inserted to the
# java command line before the -javaagent argument and before any
# arguments in javaargs.
#

usage()
{
cat <<EOF
usage: bmjava [-r rulescript | -b bootjar | -s sysjar | -nl | -nb ]* [--] javaargs

terms enclosed between [ ] are optional
terms separated by | are alternatives
a * means zero or more occurences

  -r  pass the file whose name follows this flag to the agent as
      a rule script

  -b  pass the file whose name follows this flag to the agent as
      a jar to be added to the bootstrap classpath

  -s  pass the file whose name follows this flag to the agent as
      a jar to be added to the system classpath

  -nl  do not enable the agent listener (it is enabled by default)

  -nb  do not add the byteman jar to the bootstrap classpath (it is
       added by default)

  --  optional separator to distinguish trailing arguments

  javaargs  trailing arguments to be supplied to the java command

The script constructs a -javaagent argument to pass to the java
command found in the current execution PATH. If BYTEMAN_JAVA_ARGS
is set then this is inserted to the java command line before
the -javaagent argument and before any arguments in javaargs.
EOF
exit
}

# use BYTEMAN_HOME to locate installed byteman release
if [ -z "$BYTEMAN_HOME" ]; then
# use the root of the path to this file to locate the byteman jar
    BYTEMAN_HOME=${0%*/bin/bmjava.sh}
# allow for rename to plain bmjava
    if [ "$BYTEMAN_HOME" == "$0" ]; then
	BYTEMAN_HOME=${0%*/bin/bmjava}
    fi
    if [ "$BYTEMAN_HOME" == "$0" ]; then
	echo "Unable to find byteman home"
	exit
    fi
fi

# the binary release puts byteman jar in lib while source puts it in
# build/lib so add both paths to the classpath just in case
if [ -r ${BYTEMAN_HOME}/lib/byteman.jar ]; then
    BYTEMAN_JAR=${BYTEMAN_HOME}/lib/byteman.jar
elif [ -r ${BYTEMAN_HOME}/build/lib/byteman.jar ]; then
    BYTEMAN_JAR=${BYTEMAN_HOME}/build/lib/byteman.jar
else
    echo "Cannot locate byteman jar"
    exit
fi
AGENT_PREFIX="-javaagent:${BYTEMAN_JAR}"
AGENT_OPTS=""

# default is to use listener and add byteman jar to bootstrap classpath
LISTENER=1
BYTEMAN_BOOT_JAR=1

# hmm. the asm code should be bundled in the byteman jar?
#CP=${CP}:${BYTEMAN_HOME}/ext/asm-all-3.0.jar

echo "\$# == $#" 
while [ $# -ge 1 -a "${1#-*}" != "$1" ]
do
    if [ "$1" == "-r" -a $# -ge 2 ]; then
	if [ -r "$2" ]; then
	    AGENT_OPTS="${AGENT_OPTS},script:$2"
	    shift;
	    shift;
	else
	    echo "Cannot read script $2"
	    exit
	fi
    elif [ "$1" == "-b" -a $# -ge 2 ]; then
	if [ -r "$2" ]; then
	    AGENT_OPTS="${AGENT_OPTS},boot:$2"
	    shift;
	    shift;
	else
	    echo "Cannot read boot jar $2"
	    exit
	fi
    elif [ "$1" == "-s" -a $# -ge 2 ]; then
	if [ -r "$2" ]; then
	    AGENT_OPTS="${AGENT_OPTS},sys:$2"
	    shift;
	    shift;
	else
	    echo "Cannot read system jar $2"
	    exit
	fi
    elif [ "$1" == "-nl" ]; then
	LISTENER=0
	shift;
    elif [ "$1" == "-nb" ]; then
	BYTEMAN_BOOT_JAR=0
	shift;
    elif [ "$1" == "--" ]; then
	shift;
	break;
    fi
done

if [ $BYTEMAN_BOOT_JAR -eq 1 ]; then
    AGENT_OPTS="${AGENT_OPTS},boot:${BYTEMAN_JAR}"
fi

if [ $LISTENER -eq 1 ]; then
    AGENT_OPTS="listener:true$AGENT_OPTS"
else
    AGENT_OPTS="listener:false$AGENT_OPTS"
fi

AGENT_ARGUMENT=${AGENT_PREFIX}=${AGENT_OPTS}

# allow for extra java opts via setting BYTEMAN_JAVA_OPTS

exec java ${BYTEMAN_JAVA_OPTS} ${AGENT_ARGUMENT} $*

