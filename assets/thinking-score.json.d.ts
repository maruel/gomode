// Type contract for the generated score, including hosts without resolveJsonModule.
declare const score: {
  duration: number;
  gain: number;
  attack: number;
  release: number;
  partials: { ratio: number; level: number; decay: number }[];
  echoes: { delay: number; feedback: number; level: number }[];
  notes: { at: number; midi: number; duration: number; level: number }[];
};
export default score;
