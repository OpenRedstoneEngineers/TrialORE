# TrialORE

ORE's trial management and test plugin

## Test Command Usage

| Command                 | Alias         | Permission    | Description                               |
|-------------------------|---------------|---------------|-------------------------------------------|
| `/test start`           | `/starttest`  | trialore.test | Start a test                              |
| `/test history`         |               | trialore.test | Shows your test history                   |
| `/test list [user]`     |               | trialore.list | Lists the passed tests of an individual   |
| `/test stop `           | `/stoptest`   | trialore.test | Stop your current test run                |
| `/test info [id]`       |               | trialore.list | Show all info of a test with the given ID |
| `/test check [user]`    | `/check`      | trialore.list | Check if a user passed the test           |
| `/test answer [answer]` | `/testanswer` | trialore.test | Answer a question of the test             |

## Doing a test
1. Start the test using `/test start`.
2. Once you get asked a question run `/test answer [answer]`. \
The answer should be the answer you think is correct without any prefix. For example it should not be `0b1111` but `1111`.
3. After you finished the test and passed you get a test-id. Paste it in your App. \
Staff are going to use this to verify your test.

## Full Command Usage

| Command                            | Alias                          | Description                                                       |
|------------------------------------|--------------------------------|-------------------------------------------------------------------|
| `/trial info`                      | `/trial`                       | Shows information about TrialORE                                  |
| `/trial history`                   |                                | Shows the trial history of an individual                          |
| `/trial start [user] [app url]`    | `/trialstart [user] [app url]` | Start a trial                                                     |
| `/trial note add [note]`           | `/trialnote`                   | Write a note during the trial                                     |
| `/trial note list`                 | `/trialnotes`                  | List all trial notes (opens edit interface)                       |
| `/trial note edit [noteId] [note]` |                                | Edit a trial note (designed to be used with `/trial note list`)   |
| `/trial note remove [noteId]`      |                                | Remove a trial note (designed to be used with `/trial note list`) |
| `/trial finish pass`               | `/trialpass`                   | Pass the testificate                                              |
| `/trial finish fail`               | `/trialfail`                   | Fail the testificate                                              |

## Conducting trials

1. Start the trial with `/trial start [username] [app url]`.
The username is the user's IGN and the app URL is the discourse URL.
2. Once in trial mode, you can conduct the trial "as normal".
Notes can be written by running `/trial note add [note]` or `/trialnote [note]`.
This will save this note to this trial, and will be included on the trial report.
To edit notes, run `/trial note list` or `/trialnotes` and use the pencil or x to edit or delete a note.
3. To finish a trial, you can run `/trial finish pass` (`/trialpass`) or `/trial finish fail` (`/trialfail`).
This will either accept the Testificate as Builder, or demote them back to student.

## Abandonment
There is an "abandonment" period for if a Testificate or trialer leave.
This "abandonment" period defaults to 5 minutes.
If a Testificate leaves, they will be demoted to Student and given 5 minutes to rejoin.
If they do not rejoin within those 5 minutes, the trial ends.
If a trialer leaves, the trial will automatically end in 5 minutes if they do not rejoin, demoting the Testificate to Student.
The only distinction of an abandoned trial is if the trial ends with an automated abandonment note.
