import unittest
from analyze_opencl_profile import align_event, summarize

class ProfileTests(unittest.TestCase):
    def event(self):
        return dict(queue=1, profilingStatus=[0, 0, 0, 0], executionStatus=0,
                    gpuTimesNs=[10, 20, 30, 50], hostEnqueueBeginBootNs=100)

    def test_requires_matching_valid_clock_anchor(self):
        event = self.event()
        anchor = dict(queue=1, status=0, deviceNs=60, hostBeforeBootNs=140,
                      hostAfterBootNs=160, method='marker')
        self.assertIsNone(align_event(event, []))
        self.assertIsNone(align_event(event, [dict(anchor, queue=2)]))
        self.assertIsNone(align_event(event, [dict(anchor, status=-1)]))
        self.assertIsNone(align_event(event, [dict(anchor, hostAfterBootNs=120)]))
        self.assertIsNone(align_event(event, [dict(anchor, deviceNs=40)]))
        projected = align_event(event, [anchor])
        self.assertEqual(120, projected['startBootNs'])
        self.assertEqual(140, projected['endBootNs'])
        self.assertEqual(10, projected['uncertaintyNs'])

    def report(self):
        measured = dict(warmup=False, generationMs=15, firstTextMs=3,
                        uiFrameGapsMs=[16, 20], eventCollectionMs=1,
                        openCl=dict(events=[self.event()]), output='measured')
        warm = dict(measured, warmup=True, generationMs=999, output='warmup')
        return dict(pid=1, requestedLowPriority='false', profiling=True, rounds=[warm, measured])

    def test_excludes_warmup(self):
        result = summarize(self.report())
        self.assertEqual(1, result['measuredRounds'])
        self.assertEqual(15, result['generationMs']['median'])
        self.assertEqual(['measured'], result['outputs'])

    def test_rejects_failed_profiling(self):
        report = self.report()
        report['rounds'][1]['openCl']['events'][0]['profilingStatus'] = [-1, 0, 0, 0]
        with self.assertRaises(ValueError): summarize(report)

if __name__ == '__main__': unittest.main()
