export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // GitHub squash merges list the PR's commits as long bullet lines
    'body-max-line-length': [0],
    'footer-max-line-length': [0],
  },
}
