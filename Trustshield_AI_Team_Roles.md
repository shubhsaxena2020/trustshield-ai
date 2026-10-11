# TrustShield AI — Team Roles

**Team:** Scam Scan  
**Screening:** PSIT Tech Expo 2026, 12 October 2026

These role notes describe each person's area in language they can use with judges. They distinguish project ownership from the code implementation: Shubh Saxena has written essentially all of the actual code.

## Shubh Saxena — Team Leader & Technical Lead

**What the role means here**  
Own the technical direction and integration of TrustShield AI: how the analysis pipeline, backend, machine-learning signal, security checks, interface, tests, and deployment fit together.

**Contributions so far**  
Shubh has written essentially all of the project's actual code. His work covers architecture, backend services, scam detection and model integration, security and privacy controls, integrating the application, testing, and deployment. He also led the 11 October implementation and review work for optional Supabase login, per-user scan history, and the UI/UX pass. The feature branch passed the reported backend suite of 790 tests, with 7 skipped, and received the security and accessibility reviews described in the team notes.

**Be ready to explain or demo**  
Explain how rules and the classifier contribute evidence to a score, why the chat model cannot decide the verdict, what is sent to external services, and which features are on the unmerged branch. Be ready to state the held-out metrics and limits accurately. If asked to demo login/history, identify it as a branch preview unless it has since been verified live.

## Sugandhi Agarwal — Research

**What the role means here**  
Help the team ground its problem statement and presentation in credible research, and make the need understandable to judges and users.

**Contributions so far**  
Sugandhi's contribution is research and problem framing: preparing the team to explain the reported scam statistics, why scam messages create urgency, and why people need understandable warning signs before they act. This work helps keep the opening focused on the user problem rather than on technical features.

**Be ready to explain or demo**  
Give the short problem statement. Quote the reported complaint and loss figures with the word “reported,” and explain that they show scale rather than TrustShield's detection accuracy. Do not present national statistics as results from this project.

## Vaishnavi Mishra — Research

**What the role means here**  
Support the team's understanding of the project context and help prepare clear, evidence-based answers to likely judge questions.

**Contributions so far**  
Vaishnavi's contribution is research and judge preparation: helping explain how TrustShield differs in focusing on submitted messages and multiple input formats, preparing talking points around privacy and safe actions, and helping the team answer why the product uses an assistant without letting that assistant control the verdict.

**Be ready to explain or demo**  
Explain why the project focuses on the message a person receives, what “the link is not opened” means, and the difference between a scanner verdict and an assistant explanation. If asked about advice after a financial scam, direct people to official bank channels, 1930, and cybercrime.gov.in.

## Stuti Jha — Frontend Development

**What the role means here**  
Own the user-facing perspective: understand how a person moves through the analyzer, review the clarity of the interface, and help the team demonstrate it smoothly.

**Contributions so far**  
Stuti's contribution is user-flow and interface review: trying the analyzer as a user, giving feedback on whether the screens and result wording are understandable, and learning the paths for a general chat question, a scan, and the result with its evidence. This is a presentation and product contribution; do not claim authorship of React components or other code.

**Be ready to explain or demo**  
Walk through the analyzer and explain what a user can submit, where the score and evidence appear, and how to understand the assistant's explanation. The optional login and history UI exists on the feature branch, but is not live on the production site as of this screening unless the team verifies a later deployment.

## Shruti Shukla — PPT/Presentation

**What the role means here**  
Make the team's story clear and coordinated for judges: organize the slides and spoken handoffs, keep the presentation on time, and help every speaker know where their section fits.

**Contributions so far**  
Shruti's contribution is presentation preparation: building the slide deck, organizing the explanation into a clear sequence, rehearsing delivery, keeping time, and coordinating who says what. She was absent in the previous round and is present for this round.

**Be ready to explain or demo**  
Open the presentation, introduce Scam Scan and the tagline, manage transitions, and keep the team within five minutes. Be ready to hand a technical or metric question to Shubh and a user-flow question to Stuti rather than guessing.

