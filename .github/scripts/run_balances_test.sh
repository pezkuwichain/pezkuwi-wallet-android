#!/usr/bin/env bash
adb devices

# Install debug app
adb -s emulator-5554 install app/debug/app-debug.apk

# Install instrumental tests
adb -s emulator-5554 install app/androidTest/debug/app-debug-androidTest.apk

# Run tests
adb logcat -c

# DIAGNOSTIC: stream logcat live (unbuffered) alongside the test run, so a hang shows real
# device-side activity (network calls, coroutine timeouts, ANRs) instead of just silence.
adb logcat -v time '*:D' &
LOGCAT_PID=$!

python -u - <<END
import os
import re
import subprocess as sp
import sys
import threading
import time

done = False
def update():
  # prevent CI from killing the process for inactivity
  while not done:
    time.sleep(5)
    print ("Running...")
t = threading.Thread(target=update)
t.dameon = True
t.start()

def dump_threads_if_stuck():
  # If the test is still running 3 minutes in, force a full thread/stack dump (SIGQUIT) into
  # logcat - the standard ANR-diagnosis technique - to see exactly which coroutine/thread is
  # blocked and where, instead of guessing from log filters.
  time.sleep(180)
  if done:
    return
  print("DIAGNOSTIC: still running after 3 minutes, dumping thread stacks...")
  pid = sp.run(
      'adb shell pidof io.pezkuwichain.wallet.debug', shell=True, capture_output=True, text=True
  ).stdout.strip()
  if pid:
      sp.run(f'adb shell run-as io.pezkuwichain.wallet.debug kill -3 {pid}', shell=True)
      print(f"DIAGNOSTIC: sent SIGQUIT to pid {pid}")
  else:
      print("DIAGNOSTIC: could not find pid for io.pezkuwichain.wallet.debug")
t2 = threading.Thread(target=dump_threads_if_stuck)
t2.daemon = True
t2.start()

def run():
  os.system('adb wait-for-device')
  p = sp.Popen('adb shell am instrument -w -m -e debug false -e class "io.novafoundation.nova.balances.BalancesIntegrationTest" io.pezkuwichain.wallet.debug.test/io.qameta.allure.android.runners.AllureAndroidJUnitRunner',
               shell=True, stdout=sp.PIPE, stderr=sp.PIPE, stdin=sp.PIPE)
  return p.communicate()
success = re.compile(r'OK \(\d+ tests\)')
stdout, stderr = run()
stdout = stdout.decode('ISO-8859-1')
stderr = stderr.decode('ISO-8859-1')
done = True
print (stderr)
print (stdout)
if success.search(stderr + stdout):
  sys.exit(0)
else:
  sys.exit(1) # make sure we fail if the tests fail
END
EXIT_CODE=$?
kill "$LOGCAT_PID" 2>/dev/null
adb logcat -d '*:E'

# Export results
adb exec-out run-as io.pezkuwichain.wallet.debug sh -c 'cd /data/data/io.pezkuwichain.wallet.debug/files && tar cf - allure-results' > allure-results.tar

exit $EXIT_CODE
