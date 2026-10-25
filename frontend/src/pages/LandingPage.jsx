import { Brand } from '../components/ui.jsx';

const HIGHLIGHTS = [
  ['fa-book-open', 'Course registration', 'Live seat counts with clash and credit checks.'],
  ['fa-clipboard-user', 'Attendance', 'See exactly how many classes you can still miss.'],
  ['fa-award', 'Grades and GPA', 'Exam-wise marks and a running GPA.'],
  ['fa-bell', 'Notifications', 'Exams, results and opportunities in one inbox.'],
];

/** First screen: the CampusCore entry page. Leads to sign in or sign up. */
export default function LandingPage({ onLogin, onRegister }) {
  return (
    <div className="entry">
      <section className="entry-hero">
        <div className="entry-top">Student Information System</div>

        <div className="entry-title">
          <Brand />
          <h1>Your campus.<br/>One place.</h1>
          <p>One place for courses, timetables, attendance, grades and campus notices, for every student, teacher and administrator.</p>
        </div>

        <div>
          <ul className="entry-list">
            {HIGHLIGHTS.map(([icon, title, text]) => (
              <li key={title}>
                <i className={`fa-solid ${icon}`} />
                <span><b>{title}</b>{text}</span>
              </li>
            ))}
          </ul>
          <div className="entry-foot" style={{ marginTop: 32 }}>© 2026 CampusCore</div>
        </div>
      </section>

      <section className="entry-panel">
        
        <div className="entry-card">
          <span className="kicker">Get started</span>
          <h2>Welcome</h2>
          <p>Sign in to your account, or create one in under a minute.</p>

          <button className="btn btn-lg btn-block" onClick={onLogin}>
            Sign in <i className="fa-solid fa-right-to-bracket" />
          </button>
          <div className="entry-or">New to CampusCore?</div>
          <button className="btn btn-lg btn-block btn-ghost" onClick={onRegister}>
            Create a student account <i className="fa-solid fa-arrow-right" />
          </button>

          <div className="entry-roles">
            <span><i className="fa-solid fa-user-graduate" />Students</span>
            <span><i className="fa-solid fa-chalkboard-user" />Faculty</span>
            <span><i className="fa-solid fa-user-shield" />Administrators</span>
          </div>
        </div>
      </section>
    </div>
  );
}
